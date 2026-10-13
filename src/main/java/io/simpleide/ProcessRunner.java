package io.simpleide;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 有边界超时、可取消、并且会回收整个子进程树的子进程执行器。
 *
 * <p>设计目标（对应编译/依赖解析的可靠性问题）：
 * <ul>
 *   <li>超时从此进程启动时刻开始计时，不依赖「读到 EOF 之后才 waitFor」的顺序；</li>
 *   <li>并发消费 stdout/stderr，避免管道写满导致子进程阻塞；</li>
 *   <li>输出缓冲有上限（保留头部与尾部），避免失控日志吃光堆内存；</li>
 *   <li>超时或调用方取消时先优雅终止、再强制终止，并等待进程退出；</li>
 *   <li>终止尽力覆盖子进程树（优先 ProcessHandle，其次 POSIX pkill 回退）。</li>
 * </ul>
 *
 * <p>能力边界：无法保证所有平台的强杀一定会成功（例如进程处于不可中断睡眠）。
 * 此时 {@link Result#killComplete} 会返回 false，由调用方据此标记「构建未完整完成」，
 * 而不是谎报成功。
 */
public final class ProcessRunner {

  /** 默认输出保留上限（2 MiB）。 */
  static final long DEFAULT_MAX_OUTPUT_BYTES = 2L * 1024 * 1024;
  /** 输出缓冲中头部保留字节数（截断时保留头尾便于同时看到起始信息与末尾报错）。 */
  private static final int HEAD_BYTES = 64 * 1024;
  /** 等待退出时的轮询间隔（毫秒），用于及时响应取消请求。 */
  private static final long POLL_MILLIS = 100;

  /** 禁止实例化。 */
  private ProcessRunner() {
  }

  /** 子进程输出片段监听器，用于实时进度（不承担结果解析）。 */
  public interface StreamListener {
    /**
     * 收到一段子进程输出。
     *
     * @param stream "stdout" 或 "stderr"
     * @param data 原始字节
     * @param length 有效长度
     */
    void onChunk(String stream, byte[] data, int length);
  }

  /** 子进程启动监听器：用于在进程刚创建时立即登记 PID（构建状态可查询）。 */
  public interface StartListener {
    /**
     * 子进程已创建。
     *
     * @param pid 子进程 PID；无法获取时为 -1
     */
    void onStarted(long pid);
  }

  /** 调用方取消令牌，支持超时之外的主动取消。 */
  public static final class CancelToken {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** 请求取消当前子进程。 */
    public void cancel() {
      cancelled.set(true);
    }

    /** 是否已请求取消。 */
    public boolean isCancelled() {
      return cancelled.get();
    }
  }

  /** 子进程执行结果，包含完成状态、退出码、有界输出与终止证据。 */
  public static final class Result {
    /** 进程是否成功启动。 */
    public boolean started;
    /** 进程是否在超时/取消前正常退出。 */
    public boolean finished;
    /** 是否因超时被终止。 */
    public boolean timedOut;
    /** 是否因调用方取消被终止。 */
    public boolean cancelled;
    /** 强制终止后进程树是否已确认退出；false 表示可能仍有残留。 */
    public boolean killComplete = true;
    /** 退出码；未正常退出时无意义。 */
    public int exitCode = -1;
    /** 子进程 PID；无法获取时为 -1。 */
    public long pid = -1;
    /** 有界 stdout 文本。 */
    public String stdout = "";
    /** 有界 stderr 文本（mergeStderr 为 true 时与 stdout 合并）。 */
    public String stderr = "";
    /** 输出是否因超过上限被截断。 */
    public boolean outputTruncated;
    /** 从进程启动到结束/终止的毫秒数。 */
    public long durationMs;
    /** 被终止的子进程树 PID 列表（诊断用）。 */
    public final List<Long> terminatedPids = new ArrayList<Long>();
    /** 无法确认退出的存活后代 PID（诊断用）；非空表示回收不完整。 */
    public final List<Long> survivingPids = new ArrayList<Long>();
    /** 是否成功枚举进程表（平台能力标记）。 */
    public boolean treeEnumerated = true;
    /** 启动或执行异常信息；正常时为 null。 */
    public String error;
  }

  /**
   * 执行子进程并施加真实超时（无启动回调）。
   *
   * @param command 命令行
   * @param workDir 工作目录
   * @param timeoutSec 超时秒数；小于等于 0 表示不设超时（仍可取消）
   * @param mergeStderr 是否把 stderr 合并进 stdout（单独缓冲）
   * @param maxOutputBytes 单流输出保留上限字节数
   * @param listener 可选的实时输出监听器
   * @param cancelToken 可选的取消令牌
   * @return 执行结果
   */
  public static Result run(List<String> command, Path workDir, int timeoutSec, boolean mergeStderr,
                           long maxOutputBytes, StreamListener listener, CancelToken cancelToken) {
    return run(command, workDir, timeoutSec, mergeStderr, maxOutputBytes, listener, cancelToken, null);
  }

  /**
   * 执行子进程并施加真实超时。
   *
   * @param command 命令行
   * @param workDir 工作目录
   * @param timeoutSec 超时秒数；小于等于 0 表示不设超时（仍可取消）
   * @param mergeStderr 是否把 stderr 合并进 stdout（单独缓冲）
   * @param maxOutputBytes 单流输出保留上限字节数
   * @param listener 可选的实时输出监听器
   * @param cancelToken 可选的取消令牌
   * @param startListener 可选的启动监听器，进程创建后立即回调 PID
   * @return 执行结果
   */
  public static Result run(List<String> command, Path workDir, int timeoutSec, boolean mergeStderr,
                           long maxOutputBytes, StreamListener listener, CancelToken cancelToken,
                           StartListener startListener) {
    Result result = new Result();
    long limit = maxOutputBytes > 0 ? maxOutputBytes : DEFAULT_MAX_OUTPUT_BYTES;
    Process process;
    long start = System.currentTimeMillis();
    try {
      ProcessBuilder builder = new ProcessBuilder(command);
      if (workDir != null) {
        builder.directory(workDir.toFile());
      }
      if (mergeStderr) {
        builder.redirectErrorStream(true);
      }
      process = builder.start();
      result.started = true;
      result.pid = pidOf(process);
      if (startListener != null) {
        try {
          startListener.onStarted(result.pid);
        }
        catch (Throwable ignored) {
        }
      }
    }
    catch (Exception e) {
      result.error = "子进程启动异常: " + (e.getMessage() != null ? e.getMessage() : e.toString());
      result.durationMs = System.currentTimeMillis() - start;
      return result;
    }

    BoundedBuffer outBuffer = new BoundedBuffer(HEAD_BYTES, limit);
    BoundedBuffer errBuffer = new BoundedBuffer(HEAD_BYTES, limit);
    Thread outReader = startReader(process.getInputStream(), outBuffer, "stdout", listener);
    Thread errReader = mergeStderr ? null
      : startReader(process.getErrorStream(), errBuffer, "stderr", listener);

    boolean finished = false;
    try {
      while (true) {
        if (process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
          finished = true;
          break;
        }
        if (cancelToken != null && cancelToken.isCancelled()) {
          result.cancelled = true;
          break;
        }
        if (timeoutSec > 0 && System.currentTimeMillis() - start >= timeoutSec * 1000L) {
          result.timedOut = true;
          break;
        }
      }
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      result.cancelled = true;
    }

    if (!finished) {
      terminateTree(process, result);
    }
    else {
      result.exitCode = exitCodeOf(process);
    }

    // 收敛读取线程：先给短暂机会自然读完，仍阻塞则关闭流强制 EOF
    joinQuietly(outReader, 5000);
    joinQuietly(errReader, 5000);
    closeQuietly(process.getInputStream());
    if (!mergeStderr) {
      closeQuietly(process.getErrorStream());
    }
    closeQuietly(process.getOutputStream());
    joinQuietly(outReader, 2000);
    joinQuietly(errReader, 2000);

    result.finished = finished;
    if (!finished) {
      result.exitCode = exitCodeOf(process);
    }
    result.stdout = outBuffer.toText();
    result.stderr = errBuffer.toText();
    result.outputTruncated = outBuffer.isTruncated() || errBuffer.isTruncated();
    result.durationMs = System.currentTimeMillis() - start;
    return result;
  }

  /**
   * 终止整个子进程树：先记录捕获到的后代身份，再优雅终止，最后对存活后代强制终止并独立确认。
   *
   * <p>关键点：根进程退出不构成「整棵树已退出」的证据；无法枚举进程表时必须返回
   * {@code killComplete=false}，绝不能夸大为完整回收。
   *
   * @param process 目标进程
   * @param result 结果对象，用于回填终止证据
   */
  static void terminateTree(Process process, Result result) {
    long pid = result.pid > 0 ? result.pid : pidOf(process);
    ProcessTree.Outcome outcome = ProcessTree.terminate(process, pid, 5000, 10000);
    result.terminatedPids.clear();
    result.terminatedPids.addAll(outcome.terminated);
    result.survivingPids.clear();
    result.survivingPids.addAll(outcome.surviving);
    result.treeEnumerated = outcome.enumerated;
    result.killComplete = outcome.complete;
  }

  /** 获取进程退出码，未退出时返回 -1。 */
  private static int exitCodeOf(Process process) {
    try {
      return process.exitValue();
    }
    catch (IllegalThreadStateException e) {
      return -1;
    }
  }

  /** 获取进程 PID，兼容 Java 9+（Process.pid）与 Java 8（UNIXProcess.pid 反射）。 */
  static long pidOf(Process process) {
    if (process == null) return -1;
    try {
      Method pidMethod = Process.class.getMethod("pid");
      Object value = pidMethod.invoke(process);
      if (value instanceof Long) return (Long) value;
    }
    catch (Throwable ignored) {
    }
    try {
      Field field = process.getClass().getDeclaredField("pid");
      field.setAccessible(true);
      return field.getLong(process);
    }
    catch (Throwable ignored) {
    }
    return -1;
  }

  /** 启动一个守护读取线程，把子进程输出写入有界缓冲并转发给监听器。 */
  private static Thread startReader(final InputStream in, final BoundedBuffer buffer, final String stream,
                                    final StreamListener listener) {
    Thread thread = new Thread(() -> {
      byte[] chunk = new byte[4096];
      try {
        int read;
        while ((read = in.read(chunk)) != -1) {
          buffer.write(chunk, read);
          if (listener != null) {
            try {
              listener.onChunk(stream, chunk, read);
            }
            catch (Throwable ignored) {
            }
          }
        }
      }
      catch (IOException ignored) {
      }
    }, "simple-ide-process-" + stream);
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  /** 关闭流并忽略异常。 */
  private static void closeQuietly(java.io.Closeable closeable) {
    if (closeable == null) return;
    try {
      closeable.close();
    }
    catch (Exception ignored) {
    }
  }

  /** 等待线程结束并忽略中断。 */
  private static void joinQuietly(Thread thread, long millis) {
    if (thread == null) return;
    try {
      thread.join(millis);
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * 有上限的输出缓冲：保留头部与尾部，中间丢弃，并记录是否发生截断。
   */
  static final class BoundedBuffer {
    private final int headLimit;
    private final long totalLimit;
    private final java.io.ByteArrayOutputStream head = new java.io.ByteArrayOutputStream();
    private final java.io.ByteArrayOutputStream tail = new java.io.ByteArrayOutputStream();
    private long total;
    boolean truncated;

    BoundedBuffer(int headLimit, long totalLimit) {
      this.headLimit = headLimit;
      this.totalLimit = totalLimit;
    }

    /** 追加一段输出，超出上限时只保留头尾。 */
    synchronized void write(byte[] data, int length) {
      total += length;
      int headRemaining = headLimit - head.size();
      if (headRemaining > 0) {
        int headPart = Math.min(headRemaining, length);
        head.write(data, 0, headPart);
        if (headPart == length) return;
        tail.write(data, headPart, length - headPart);
      }
      else {
        tail.write(data, 0, length);
      }
      trimTail();
    }

    /** 尾部缓冲超过上限时丢弃中间内容，避免大日志导致 O(n^2) 拷贝与内存膨胀。 */
    private void trimTail() {
      int tailLimit = (int) Math.max(0, totalLimit - headLimit);
      if (tail.size() <= tailLimit + 65536) return;
      byte[] all = tail.toByteArray();
      tail.reset();
      int start = all.length - tailLimit;
      if (start > 0) {
        truncated = true;
        tail.write(all, start, tailLimit);
      }
      else {
        tail.write(all, 0, all.length);
      }
    }

    /** 输出上限是否被突破。 */
    synchronized boolean isTruncated() {
      return truncated || total > totalLimit;
    }

    /** 解码为文本；截断时插入显式标记，避免误读为完整日志。 */
    synchronized String toText() {
      StringBuilder sb = new StringBuilder();
      sb.append(new String(head.toByteArray(), StandardCharsets.UTF_8));
      String tailText = new String(tail.toByteArray(), StandardCharsets.UTF_8);
      if (isTruncated()) {
        sb.append("\n... [输出超过 ").append(totalLimit)
          .append(" 字节上限，中间内容已截断，总字节数 ").append(total).append("] ...\n");
      }
      sb.append(tailText);
      return sb.toString();
    }
  }
}
