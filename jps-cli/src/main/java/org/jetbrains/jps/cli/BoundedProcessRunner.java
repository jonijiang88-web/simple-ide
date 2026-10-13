package org.jetbrains.jps.cli;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * jps-cli 内部使用的最小有界子进程执行器。
 *
 * <p>用于「通过 Maven 解析依赖」这类外部调用：必须在有限时间内结束、并发消费输出、
 * 超时后回收整个子进程树，并保留失败证据，避免父进程无限阻塞或遗留孤儿进程
 * 与同项目的新构建并发写同一缓存。
 */
public final class BoundedProcessRunner {

    /** 单流输出保留上限（1 MiB），超出时保留头尾。 */
    private static final long DEFAULT_MAX_OUTPUT_BYTES = 1024 * 1024;
    /** 头部保留字节数。 */
    private static final int HEAD_BYTES = 32 * 1024;
    /** 退出等待轮询间隔（毫秒）。 */
    private static final long POLL_MILLIS = 100;

    /** 禁止实例化。 */
    private BoundedProcessRunner() {
    }

    /** 执行结果。 */
    public static final class Result {
        /** 是否成功启动。 */
        public boolean started;
        /** 是否在超时前正常退出。 */
        public boolean finished;
        /** 是否因超时被终止。 */
        public boolean timedOut;
        /** 强制终止后进程树是否确认退出（无法确认时必须为 false）。 */
        public boolean killComplete = true;
        /** 被终止的进程 PID 列表（诊断用）。 */
        public final List<Long> terminatedPids = new ArrayList<Long>();
        /** 无法确认退出的存活后代 PID（非空表示回收不完整）。 */
        public final List<Long> survivingPids = new ArrayList<Long>();
        /** 是否成功枚举进程表（平台能力标记）。 */
        public boolean treeEnumerated = true;
        /** 退出码。 */
        public int exitCode = -1;
        /** 子进程 PID。 */
        public long pid = -1;
        /** 有界合并输出（stdout+stderr）。 */
        public String output = "";
        /** 是否发生输出截断。 */
        public boolean outputTruncated;
        /** 耗时毫秒。 */
        public long durationMs;
        /** 启动异常信息。 */
        public String error;
    }

    /**
     * 执行子进程并施加有限超时。
     *
     * @param command 命令行
     * @param workDir 工作目录
     * @param timeoutSec 超时秒数，小于等于 0 表示不设超时
     * @param maxOutputBytes 输出保留上限
     * @return 执行结果
     */
    public static Result run(List<String> command, Path workDir, int timeoutSec, long maxOutputBytes) {
        Result result = new Result();
        long limit = maxOutputBytes > 0 ? maxOutputBytes : DEFAULT_MAX_OUTPUT_BYTES;
        long start = System.currentTimeMillis();
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (workDir != null) {
                builder.directory(workDir.toFile());
            }
            builder.redirectErrorStream(true);
            process = builder.start();
            result.started = true;
            result.pid = pidOf(process);
        } catch (Exception e) {
            result.error = "子进程启动异常: " + (e.getMessage() != null ? e.getMessage() : e.toString());
            result.durationMs = System.currentTimeMillis() - start;
            return result;
        }

        final ByteArrayOutputStream head = new ByteArrayOutputStream();
        final ByteArrayOutputStream tail = new ByteArrayOutputStream();
        final long[] total = new long[]{0};
        final boolean[] truncated = new boolean[]{false};
        final int tailLimit = (int) Math.max(0, limit - HEAD_BYTES);

        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] chunk = new byte[4096];
                try {
                    int read;
                    while ((read = process.getInputStream().read(chunk)) != -1) {
                        synchronized (total) {
                            total[0] += read;
                            int headRemaining = HEAD_BYTES - head.size();
                            if (headRemaining > 0) {
                                int headPart = Math.min(headRemaining, read);
                                head.write(chunk, 0, headPart);
                                if (headPart < read) {
                                    tail.write(chunk, headPart, read - headPart);
                                }
                            } else {
                                tail.write(chunk, 0, read);
                            }
                            if (tail.size() > tailLimit + 65536) {
                                byte[] all = tail.toByteArray();
                                tail.reset();
                                int skip = all.length - tailLimit;
                                if (skip > 0) {
                                    truncated[0] = true;
                                    tail.write(all, skip, tailLimit);
                                } else {
                                    tail.write(all, 0, all.length);
                                }
                            }
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        }, "jps-cli-process-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished = false;
        try {
            while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                if (timeoutSec > 0 && System.currentTimeMillis() - start >= timeoutSec * 1000L) {
                    result.timedOut = true;
                    break;
                }
            }
            if (!result.timedOut) {
                finished = true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.timedOut = true;
        }

        if (!finished) {
            terminateTree(process, result);
        } else {
            result.exitCode = exitCodeOf(process);
        }

        joinQuietly(reader, 5000);
        closeQuietly(process.getInputStream());
        closeQuietly(process.getOutputStream());
        joinQuietly(reader, 2000);

        result.finished = finished;
        if (!finished) {
            result.exitCode = exitCodeOf(process);
        }
        StringBuilder sb = new StringBuilder();
        synchronized (total) {
            sb.append(new String(head.toByteArray(), StandardCharsets.UTF_8));
            if (truncated[0] || total[0] > limit) {
                sb.append("\n... [输出超过 ").append(limit).append(" 字节上限，中间内容已截断，总字节数 ")
                        .append(total[0]).append("] ...\n");
            }
            sb.append(new String(tail.toByteArray(), StandardCharsets.UTF_8));
        }
        result.output = sb.toString();
        result.outputTruncated = truncated[0] || total[0] > limit;
        result.durationMs = System.currentTimeMillis() - start;
        return result;
    }

    /** 先捕获后代身份、再优雅终止、最后对存活后代强制终止并独立确认。 */
    private static void terminateTree(Process process, Result result) {
        long pid = result.pid > 0 ? result.pid : ProcessTree.pidOf(process);
        ProcessTree.Outcome outcome = ProcessTree.terminate(process, pid, 5000, 10000);
        result.terminatedPids.clear();
        result.terminatedPids.addAll(outcome.terminated);
        result.survivingPids.clear();
        result.survivingPids.addAll(outcome.surviving);
        result.treeEnumerated = outcome.enumerated;
        result.killComplete = outcome.complete;
    }

    /** 获取退出码。 */
    private static int exitCodeOf(Process process) {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException e) {
            return -1;
        }
    }

    /** 获取 PID（复用进程树工具，避免两处实现分叉）。 */
    static long pidOf(Process process) {
        return ProcessTree.pidOf(process);
    }

    /** 静默关闭流。 */
    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    /** 等待线程结束并忽略中断。 */
    private static void joinQuietly(Thread thread, long millis) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
