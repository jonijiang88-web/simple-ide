package io.simpleide;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 进程树枚举与身份化终止。
 *
 * <p>核心约束（修复「根进程退出即宣称整棵树已退出」的误报）：
 * <ul>
 *   <li>第一次发送任何终止信号之前，先枚举并记录后代进程的 PID 与身份令牌（启动时间），
 *       而不是事后用 {@code pkill -P} 猜一层；</li>
 *   <li>根进程退出不作为「整棵树已退出」的依据，必须对捕获到的每个后代独立确认；</li>
 *   <li>忽略 TERM 的存活后代会被强制终止，并再次确认；</li>
 *   <li>发送强杀前重新校验身份令牌，避免 PID 复用导致误杀无关进程；</li>
 *   <li>无法枚举进程表（平台不支持）时不做「完整回收」承诺：{@code complete=false}。</li>
 * </ul>
 */
final class ProcessTree {

  /** 进程身份令牌缺失时的占位符（只能退化为存在性判断）。 */
  private static final String NO_TOKEN = "";

  /** 进程表枚举策略系统属性，便于按平台/测试强制选择策略。 */
  static final String STRATEGY_PROPERTY = "simple.ide.proc.table";

  /** 单个进程表项。 */
  static final class Proc {
    /** 进程 PID。 */
    final long pid;
    /** 父进程 PID；未知为 -1。 */
    final long parentPid;
    /** 身份令牌（启动时间），用于防止 PID 复用误判/误杀；未知为空串。 */
    final String token;

    Proc(long pid, long parentPid, String token) {
      this.pid = pid;
      this.parentPid = parentPid;
      this.token = token == null ? NO_TOKEN : token;
    }
  }

  /** 终止结果。 */
  static final class Outcome {
    /** 是否确认整棵树（根 + 全部捕获后代）都已退出；无法枚举时为 false。 */
    boolean complete;
    /** 是否成功枚举到进程表；false 表示平台不支持，不能承诺完整回收。 */
    boolean enumerated;
    /** 被终止的 PID（含强杀）。 */
    final List<Long> terminated = new ArrayList<Long>();
    /** 仍存活、无法确认退出的 PID。 */
    final List<Long> surviving = new ArrayList<Long>();
    /** 枚举策略说明，用于诊断。 */
    String strategy = "none";
  }

  /** 禁止实例化。 */
  private ProcessTree() {
  }

  /**
   * 读取当前进程表。
   *
   * @return PID → 进程信息；无法枚举时返回空表
   */
  static Map<Long, Proc> processTable() {
    String strategy = System.getProperty(STRATEGY_PROPERTY, "auto").trim().toLowerCase();
    Map<Long, Proc> table = new LinkedHashMap<Long, Proc>();
    if ("handle".equals(strategy) || "auto".equals(strategy)) {
      if (fillFromProcessHandle(table)) return table;
    }
    if ("proc".equals(strategy) || "auto".equals(strategy)) {
      if (fillFromProc(table)) return table;
    }
    if ("ps".equals(strategy) || "auto".equals(strategy)) {
      fillFromPs(table);
      return table;
    }
    return table;
  }

  /** 当前枚举策略名称（用于结果诊断）。 */
  static String strategyName() {
    String strategy = System.getProperty(STRATEGY_PROPERTY, "auto").trim().toLowerCase();
    if (!"auto".equals(strategy)) return strategy;
    Map<Long, Proc> ignored = new LinkedHashMap<Long, Proc>();
    if (tryFillFromProcessHandle(ignored)) return "handle";
    if (tryFillFromProc(ignored)) return "proc";
    return "ps";
  }

  /** 是否能够枚举本机进程表。 */
  static boolean canEnumerate() {
    return !processTable().isEmpty();
  }

  /** 获取当前 JVM 进程 PID（用于排除自身）。 */
  static long currentPid() {
    try {
      String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
      int at = name.indexOf('@');
      if (at > 0) return Long.parseLong(name.substring(0, at));
    }
    catch (Throwable ignored) {
    }
    return -1;
  }

  /**
   * 收集指定根进程的全部后代（广度优先展开，返回顺序为「由深到浅」，便于先终止子孙）。
   *
   * @param table 进程表
   * @param rootPid 根 PID
   * @return 后代列表（不含根）
   */
  static List<Proc> descendants(Map<Long, Proc> table, long rootPid) {
    List<Proc> ordered = new ArrayList<Proc>();
    Set<Long> seen = new LinkedHashSet<Long>();
    seen.add(rootPid);
    List<Long> current = new ArrayList<Long>();
    current.add(rootPid);
    // 逐层展开并记录深度，最终反转输出（最深层在前）
    List<List<Proc>> levels = new ArrayList<List<Proc>>();
    while (!current.isEmpty()) {
      List<Proc> level = new ArrayList<Proc>();
      List<Long> next = new ArrayList<Long>();
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
   * 终止进程树：先记录后代身份，再优雅终止，最后对存活后代强制终止并独立确认。
   *
   * @param root 根进程
   * @param rootPid 根 PID（小于等于 0 时尝试自行解析）
   * @param graceMs 首次优雅终止的等待毫秒数
   * @param forceMs 强制终止后的等待毫秒数
   * @return 终止结果
   */
  static Outcome terminate(Process root, long rootPid, long graceMs, long forceMs) {
    Outcome outcome = new Outcome();
    long pid = rootPid > 0 ? rootPid : pidOf(root);
    outcome.strategy = strategyName();

    // 1) 第一次发送信号之前：捕获全部后代及其身份令牌
    Map<Long, Proc> before = processTable();
    outcome.enumerated = !before.isEmpty();
    List<Proc> captured = pid > 0 ? descendants(before, pid) : new ArrayList<Proc>();

    // 2) 优雅终止：先子孙（由深到浅），再根进程
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
      }
      catch (Exception ignored) {
      }
      rootExited = awaitExit(root, graceMs);
    }
    else {
      signal(pid, false);
    }

    // 3) 独立确认后代是否退出；根退出不作为整棵树的判据
    Map<Long, Proc> after = processTable();
    List<Proc> survivors = new ArrayList<Proc>();
    for (Proc proc : captured) {
      if (isAlive(after, proc)) survivors.add(proc);
    }
    if (root == null) {
      rootExited = !isAliveByPid(after, pid);
    }

    // 4) 强制终止存活后代（含存活节点新产生的子孙），发送前再次校验身份
    if (!survivors.isEmpty() || !rootExited) {
      List<Proc> targets = new ArrayList<Proc>(survivors);
      for (Proc survivor : survivors) {
        for (Proc child : descendants(processTable(), survivor.pid)) {
          targets.add(child);
        }
      }
      for (Proc proc : targets) {
        Map<Long, Proc> current = processTable();
        if (!isAlive(current, proc)) continue;
        signal(proc.pid, true);
        outcome.terminated.add(proc.pid);
      }
      if (root != null && !rootExited) {
        try {
          root.destroyForcibly();
        }
        catch (Exception ignored) {
        }
        rootExited = awaitExit(root, forceMs);
      }
      else if (root == null) {
        signal(pid, true);
      }
      sleepQuietly(200);
    }

    // 5) 最终确认：只有「成功枚举 + 根已退出 + 无存活后代」才算完整回收
    Map<Long, Proc> finalTable = processTable();
    for (Proc proc : captured) {
      if (isAlive(finalTable, proc)) outcome.surviving.add(proc.pid);
    }
    for (Proc proc : survivors) {
      if (isAlive(finalTable, proc) && !outcome.surviving.contains(proc.pid)) {
        outcome.surviving.add(proc.pid);
      }
    }
    outcome.complete = outcome.enumerated && rootExited && outcome.surviving.isEmpty();
    return outcome;
  }

  /**
   * 判断捕获的后代是否仍存活（按身份令牌校验，避免 PID 复用误判）。
   *
   * @param table 当前进程表
   * @param proc 捕获的后代
   * @return 仍存活返回 true
   */
  static boolean isAlive(Map<Long, Proc> table, Proc proc) {
    Proc current = table.get(proc.pid);
    if (current == null) return false;
    if (proc.token.isEmpty() || current.token.isEmpty()) return true;
    return proc.token.equals(current.token);
  }

  /** 判断指定 PID 是否存活（存在性判断，无法校验身份）。 */
  private static boolean isAliveByPid(Map<Long, Proc> table, long pid) {
    return pid > 0 && table.containsKey(pid);
  }

  /**
   * 对单个 PID 发送信号。
   *
   * @param pid 目标 PID
   * @param forcibly 是否强制终止
   */
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
    }
    catch (Throwable ignored) {
      // 运行时无 ProcessHandle（Java 8）：退回 POSIX kill
    }
    runQuietly("kill", forcibly ? "-KILL" : "-TERM", Long.toString(pid));
  }

  /** 查找命令行中包含指定片段的进程 PID（用于检测遗留的 JPS 构建进程）。 */
  static List<Long> findByCommandLine(String needle) {
    List<Long> result = new ArrayList<Long>();
    if (needle == null || needle.isEmpty()) return result;
    for (Map.Entry<Long, Proc> entry : processTable().entrySet()) {
      String cmdline = commandLineOf(entry.getKey());
      if (cmdline != null && cmdline.contains(needle)) result.add(entry.getKey());
    }
    return result;
  }

  /** 读取指定进程的命令行；无法读取时返回 null。 */
  static String commandLineOf(long pid) {
    // Java 9+：ProcessHandle.info().commandLine()
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
    }
    catch (Throwable ignored) {
    }
    // Linux：/proc/<pid>/cmdline
    Path cmdline = Paths.get("/proc", Long.toString(pid), "cmdline");
    if (Files.isReadable(cmdline)) {
      try {
        byte[] bytes = Files.readAllBytes(cmdline);
        return new String(bytes, StandardCharsets.UTF_8).replace('\0', ' ').trim();
      }
      catch (Exception ignored) {
      }
    }
    return null;
  }

  /** 通过 ProcessHandle 填充进程表（Java 9+）。 */
  private static boolean fillFromProcessHandle(Map<Long, Proc> table) {
    return tryFillFromProcessHandle(table);
  }

  /** 尝试通过 ProcessHandle 枚举；失败返回 false。 */
  private static boolean tryFillFromProcessHandle(Map<Long, Proc> table) {
    try {
      Class<?> handleClass = Class.forName("java.lang.ProcessHandle");
      Method allProcesses = handleClass.getMethod("allProcesses");
      Object stream = allProcesses.invoke(null);
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
        }
        catch (Throwable ignored) {
        }
      });
      return !table.isEmpty();
    }
    catch (Throwable ignored) {
      return false;
    }
  }

  /** 通过 /proc 填充进程表（Linux，Java 8 可用）。 */
  private static boolean fillFromProc(Map<Long, Proc> table) {
    return tryFillFromProc(table);
  }

  /** 尝试通过 /proc 枚举；失败返回 false。 */
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

  /** 解析 /proc/<pid>/stat，提取父进程 PID 与 starttime 作为身份令牌。 */
  private static Proc readProcStat(long pid) {
    File stat = new File("/proc/" + pid + "/stat");
    if (!stat.isFile()) return null;
    try (BufferedReader reader = new BufferedReader(new FileReader(stat))) {
      String line = reader.readLine();
      if (line == null) return null;
      int closeParen = line.lastIndexOf(')');
      if (closeParen < 0 || closeParen + 2 >= line.length()) return null;
      String[] fields = line.substring(closeParen + 2).trim().split("\\s+");
      // fields[0]=state(3rd) fields[1]=ppid(4th) ... fields[19]=starttime(22nd)
      if (fields.length < 20) return null;
      long parentPid = Long.parseLong(fields[1]);
      String token = "proc-start=" + fields[19];
      return new Proc(pid, parentPid, token);
    }
    catch (Exception e) {
      return null;
    }
  }

  /** 通过 POSIX ps 填充进程表（结构可枚举、身份仅能退化为父 PID）。 */
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
      // ps 无可靠启动时间：身份令牌退化为父 PID，仅可防止「同号不同父」的误杀
      table.put(pid, new Proc(pid, parentPid, "ps-ppid=" + parentPid));
    }
  }

  /** 判断字符串是否为纯数字。 */
  private static boolean isNumeric(String value) {
    if (value == null || value.isEmpty()) return false;
    for (int i = 0; i < value.length(); i++) {
      if (!Character.isDigit(value.charAt(i))) return false;
    }
    return true;
  }

  /** 获取进程 PID，兼容 Java 8 与 Java 9+。 */
  static long pidOf(Process process) {
    if (process == null) return -1;
    try {
      Object value = Process.class.getMethod("pid").invoke(process);
      if (value instanceof Number) return ((Number) value).longValue();
    }
    catch (Throwable ignored) {
    }
    try {
      java.lang.reflect.Field field = process.getClass().getDeclaredField("pid");
      field.setAccessible(true);
      return field.getLong(process);
    }
    catch (Throwable ignored) {
    }
    return -1;
  }

  /** 等待进程退出。 */
  private static boolean awaitExit(Process process, long millis) {
    try {
      return process.waitFor(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return !process.isAlive();
    }
  }

  /** 执行命令并忽略失败。 */
  private static void runQuietly(String... argv) {
    runAndCapture(argv);
  }

  /** 执行命令并返回合并输出；失败返回 null。 */
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
    }
    catch (Exception e) {
      return null;
    }
  }

  /** 安静地等待。 */
  private static void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** 返回不可修改的空列表。 */
  static List<Long> emptyPids() {
    return Collections.emptyList();
  }
}
