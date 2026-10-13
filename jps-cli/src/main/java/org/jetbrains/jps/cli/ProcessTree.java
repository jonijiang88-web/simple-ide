package org.jetbrains.jps.cli;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 进程树枚举与身份化终止（jps-cli 侧实现）。
 *
 * <p>与 simple-ide 侧保持同一语义：发送任何终止信号之前先捕获后代 PID 与身份令牌；
 * 根进程退出不作为整棵树已退出的依据；对忽略 TERM 的存活后代强制终止并独立确认；
 * 无法枚举进程表时不得声称完整回收。
 */
final class ProcessTree {

    /** 身份令牌缺失占位符。 */
    private static final String NO_TOKEN = "";

    /** 进程表枚举策略系统属性。 */
    static final String STRATEGY_PROPERTY = "simple.ide.proc.table";

    /** 单个进程表项。 */
    static final class Proc {
        final long pid;
        final long parentPid;
        final String token;

        Proc(long pid, long parentPid, String token) {
            this.pid = pid;
            this.parentPid = parentPid;
            this.token = token == null ? NO_TOKEN : token;
        }
    }

    /** 终止结果。 */
    static final class Outcome {
        boolean complete;
        boolean enumerated;
        final List<Long> terminated = new ArrayList<>();
        final List<Long> surviving = new ArrayList<>();
        String strategy = "none";
    }

    private ProcessTree() {
    }

    /**
     * 读取当前进程表
     *
     * @return PID → 进程信息；无法枚举时返回空表
     */
    static Map<Long, Proc> processTable() {
        String strategy = System.getProperty(STRATEGY_PROPERTY, "auto").trim().toLowerCase();
        Map<Long, Proc> table = new LinkedHashMap<>();
        if ("handle".equals(strategy) || "auto".equals(strategy)) {
            if (tryFillFromProcessHandle(table)) return table;
        }
        if ("proc".equals(strategy) || "auto".equals(strategy)) {
            if (tryFillFromProc(table)) return table;
        }
        if ("ps".equals(strategy) || "auto".equals(strategy)) {
            fillFromPs(table);
            return table;
        }
        return table;
    }

    /** 当前枚举策略名称。 */
    static String strategyName() {
        String strategy = System.getProperty(STRATEGY_PROPERTY, "auto").trim().toLowerCase();
        if (!"auto".equals(strategy)) return strategy;
        Map<Long, Proc> probe = new LinkedHashMap<>();
        if (tryFillFromProcessHandle(probe)) return "handle";
        if (tryFillFromProc(probe)) return "proc";
        return "ps";
    }

    /** 是否可枚举本机进程表。 */
    static boolean canEnumerate() {
        return !processTable().isEmpty();
    }

    /**
     * 收集全部后代（由深到浅）
     *
     * @param table 进程表
     * @param rootPid 根 PID
     * @return 后代列表（不含根）
     */
    static List<Proc> descendants(Map<Long, Proc> table, long rootPid) {
        List<Proc> ordered = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        seen.add(rootPid);
        List<Long> current = new ArrayList<>();
        current.add(rootPid);
        List<List<Proc>> levels = new ArrayList<>();
        while (!current.isEmpty()) {
            List<Proc> level = new ArrayList<>();
            List<Long> next = new ArrayList<>();
            for (Proc proc : table.values()) {
                if (proc.pid == rootPid) continue;
                if (current.contains(proc.parentPid) && seen.add(proc.pid)) {
                    level.add(proc);
                    next.add(proc.pid);
                }
            }
            if (!level.isEmpty()) levels.add(level);
            current = next;
        }
        for (int i = levels.size() - 1; i >= 0; i--) {
            ordered.addAll(levels.get(i));
        }
        return ordered;
    }

    /**
     * 终止进程树：先捕获后代身份，再优雅终止，最后对存活后代强制终止并独立确认
     *
     * @param root 根进程
     * @param rootPid 根 PID
     * @param graceMs 优雅终止等待毫秒
     * @param forceMs 强制终止后等待毫秒
     * @return 终止结果
     */
    static Outcome terminate(Process root, long rootPid, long graceMs, long forceMs) {
        Outcome outcome = new Outcome();
        long pid = rootPid > 0 ? rootPid : pidOf(root);
        outcome.strategy = strategyName();

        Map<Long, Proc> before = processTable();
        outcome.enumerated = !before.isEmpty();
        List<Proc> captured = pid > 0 ? descendants(before, pid) : new ArrayList<Proc>();

        for (Proc proc : captured) {
            if (isAlive(before, proc)) {
                signal(proc.pid, false);
                outcome.terminated.add(proc.pid);
            }
        }
        boolean rootExited = false;
        if (root != null) {
            try {
                root.destroy();
            } catch (Exception ignored) {
            }
            rootExited = awaitExit(root, graceMs);
        } else {
            signal(pid, false);
        }

        Map<Long, Proc> after = processTable();
        List<Proc> survivors = new ArrayList<>();
        for (Proc proc : captured) {
            if (isAlive(after, proc)) survivors.add(proc);
        }
        if (root == null) {
            rootExited = !after.containsKey(pid);
        }

        if (!survivors.isEmpty() || !rootExited) {
            List<Proc> targets = new ArrayList<>(survivors);
            for (Proc survivor : survivors) {
                targets.addAll(descendants(processTable(), survivor.pid));
            }
            for (Proc proc : targets) {
                if (!isAlive(processTable(), proc)) continue;
                signal(proc.pid, true);
                outcome.terminated.add(proc.pid);
            }
            if (root != null && !rootExited) {
                try {
                    root.destroyForcibly();
                } catch (Exception ignored) {
                }
                rootExited = awaitExit(root, forceMs);
            } else if (root == null) {
                signal(pid, true);
            }
            sleepQuietly(200);
        }

        Map<Long, Proc> finalTable = processTable();
        for (Proc proc : captured) {
            if (isAlive(finalTable, proc)) outcome.surviving.add(proc.pid);
        }
        outcome.complete = outcome.enumerated && rootExited && outcome.surviving.isEmpty();
        return outcome;
    }

    /** 按身份令牌判断捕获的后代是否仍存活。 */
    static boolean isAlive(Map<Long, Proc> table, Proc proc) {
        Proc current = table.get(proc.pid);
        if (current == null) return false;
        if (proc.token.isEmpty() || current.token.isEmpty()) return true;
        return proc.token.equals(current.token);
    }

    /** 对 PID 发送信号。 */
    static void signal(long pid, boolean forcibly) {
        if (pid <= 0) return;
        try {
            Class<?> handleClass = Class.forName("java.lang.ProcessHandle");
            Object optional = handleClass.getMethod("of", long.class).invoke(null, Long.valueOf(pid));
            if (optional instanceof Optional && ((Optional<?>) optional).isPresent()) {
                Object handle = ((Optional<?>) optional).get();
                handleClass.getMethod(forcibly ? "destroyForcibly" : "destroy").invoke(handle);
                return;
            }
        } catch (Throwable ignored) {
        }
        runAndCapture("kill", forcibly ? "-KILL" : "-TERM", Long.toString(pid));
    }

    /** 查找命令行包含指定片段的进程 PID，用于检测遗留构建进程。 */
    static List<Long> findByCommandLine(String needle) {
        List<Long> result = new ArrayList<>();
        if (needle == null || needle.isEmpty()) return result;
        for (Long pid : processTable().keySet()) {
            String cmdline = commandLineOf(pid);
            if (cmdline != null && cmdline.contains(needle)) result.add(pid);
        }
        return result;
    }

    /** 读取进程命令行；失败返回 null。 */
    static String commandLineOf(long pid) {
        try {
            Class<?> handleClass = Class.forName("java.lang.ProcessHandle");
            Object optional = handleClass.getMethod("of", long.class).invoke(null, Long.valueOf(pid));
            if (optional instanceof Optional && ((Optional<?>) optional).isPresent()) {
                Object handle = ((Optional<?>) optional).get();
                Object info = handleClass.getMethod("info").invoke(handle);
                Object cmd = info.getClass().getMethod("commandLine").invoke(info);
                if (cmd instanceof Optional && ((Optional<?>) cmd).isPresent()) {
                    return String.valueOf(((Optional<?>) cmd).get());
                }
            }
        } catch (Throwable ignored) {
        }
        Path cmdline = Paths.get("/proc", Long.toString(pid), "cmdline");
        if (Files.isReadable(cmdline)) {
            try {
                return new String(Files.readAllBytes(cmdline), StandardCharsets.UTF_8).replace('\0', ' ').trim();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** 通过 ProcessHandle 枚举（Java 9+）。 */
    private static boolean tryFillFromProcessHandle(Map<Long, Proc> table) {
        try {
            Class<?> handleClass = Class.forName("java.lang.ProcessHandle");
            Object stream = handleClass.getMethod("allProcesses").invoke(null);
            if (!(stream instanceof java.util.stream.Stream)) return false;
            Method pidMethod = handleClass.getMethod("pid");
            Method parentMethod = handleClass.getMethod("parent");
            Method infoMethod = handleClass.getMethod("info");
            @SuppressWarnings("unchecked")
            java.util.stream.Stream<Object> handles = (java.util.stream.Stream<Object>) stream;
            handles.forEach(handle -> {
                try {
                    long pid = ((Number) pidMethod.invoke(handle)).longValue();
                    long parentPid = -1;
                    Object parentOptional = parentMethod.invoke(handle);
                    if (parentOptional instanceof Optional && ((Optional<?>) parentOptional).isPresent()) {
                        parentPid = ((Number) pidMethod.invoke(((Optional<?>) parentOptional).get())).longValue();
                    }
                    String token = NO_TOKEN;
                    Object info = infoMethod.invoke(handle);
                    Object start = info.getClass().getMethod("startInstant").invoke(info);
                    if (start instanceof Optional && ((Optional<?>) start).isPresent()) {
                        token = "start=" + String.valueOf(((Optional<?>) start).get());
                    }
                    table.put(pid, new Proc(pid, parentPid, token));
                } catch (Throwable ignored) {
                }
            });
            return !table.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 通过 /proc 枚举（Linux / Java 8）。 */
    private static boolean tryFillFromProc(Map<Long, Proc> table) {
        Path procRoot = Paths.get("/proc");
        if (!Files.isDirectory(procRoot)) return false;
        File[] entries = procRoot.toFile().listFiles();
        if (entries == null) return false;
        int read = 0;
        for (File entry : entries) {
            String name = entry.getName();
            if (!isNumeric(name)) continue;
            long pid = Long.parseLong(name);
            Proc proc = readProcStat(pid);
            if (proc != null) {
                table.put(pid, proc);
                read++;
            }
        }
        return read > 0;
    }

    /** 解析 /proc/<pid>/stat。 */
    private static Proc readProcStat(long pid) {
        File stat = new File("/proc/" + pid + "/stat");
        if (!stat.isFile()) return null;
        try (BufferedReader reader = new BufferedReader(new FileReader(stat))) {
            String line = reader.readLine();
            if (line == null) return null;
            int closeParen = line.lastIndexOf(')');
            if (closeParen < 0 || closeParen + 2 >= line.length()) return null;
            String[] fields = line.substring(closeParen + 2).trim().split("\\s+");
            if (fields.length < 20) return null;
            long parentPid = Long.parseLong(fields[1]);
            return new Proc(pid, parentPid, "proc-start=" + fields[19]);
        } catch (Exception e) {
            return null;
        }
    }

    /** 通过 ps 枚举（身份退化为父 PID）。 */
    private static void fillFromPs(Map<Long, Proc> table) {
        String output = runAndCapture("ps", "-eo", "pid=,ppid=");
        if (output == null) return;
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 2 || !isNumeric(parts[0]) || !isNumeric(parts[1])) continue;
            long pid = Long.parseLong(parts[0]);
            long parentPid = Long.parseLong(parts[1]);
            table.put(pid, new Proc(pid, parentPid, "ps-ppid=" + parentPid));
        }
    }

    /** 纯数字判断。 */
    private static boolean isNumeric(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return false;
        }
        return true;
    }

    /** 获取当前 JVM 进程 PID（用于锁文件与自身排除）。 */
    static long currentPid() {
        try {
            String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            int at = name.indexOf('@');
            if (at > 0) return Long.parseLong(name.substring(0, at));
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /** 获取 PID，兼容 Java 8 与 Java 9+。 */
    static long pidOf(Process process) {
        if (process == null) return -1;
        try {
            Object value = Process.class.getMethod("pid").invoke(process);
            if (value instanceof Number) return ((Number) value).longValue();
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Field field = process.getClass().getDeclaredField("pid");
            field.setAccessible(true);
            return field.getLong(process);
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /** 等待进程退出。 */
    private static boolean awaitExit(Process process, long millis) {
        try {
            return process.waitFor(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !process.isAlive();
        }
    }

    /** 执行命令并返回输出；失败返回 null。 */
    private static String runAndCapture(String... argv) {
        try {
            ProcessBuilder builder = new ProcessBuilder(argv);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 安静等待。 */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
