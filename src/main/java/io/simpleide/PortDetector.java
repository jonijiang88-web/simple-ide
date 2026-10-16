package io.simpleide;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从运行中的进程树反查监听端口，使未显式指定 --port 的应用也能自动获得真实主端口。
 *
 * 探测来源为操作系统套接字表（Linux /proc、macOS lsof、Windows netstat），不依赖具体框架配置，
 * 因此对 Spring Boot、Node 等应用同样有效；多候选时优先采信启动日志里框架自报的端口。
 */
final class PortDetector {

  /** Spring Boot DevTools 自动启用的 LiveReload 端口，不属于应用业务端口。 */
  static final int LIVERELOAD_PORT = 35729;

  /** 单个平台探测命令的超时时间（毫秒），避免拖慢 status/list。 */
  private static final long PROBE_TIMEOUT_MS = 2000L;

  /**
   * 日志中框架自报端口的匹配模式，按「越具体越优先」排列：
   * Tomcat/Netty 等容器启动文案、通用 on port 文案、形如 http://host:port 的访问地址。
   */
  private static final Pattern[] LOG_PORT_PATTERNS = {
    Pattern.compile("(?:tomcat|netty|undertow|jetty|webserver)\\s+started\\s+on\\s+ports?\\s*\\(?s?\\)?:?\\s*(\\d{2,5})", Pattern.CASE_INSENSITIVE),
    Pattern.compile("on\\s+ports?\\s*\\(?s?\\)?:?\\s*(\\d{2,5})", Pattern.CASE_INSENSITIVE),
    Pattern.compile("https?://[\\w.\\-]+:(\\d{2,5})")
  };

  /** 禁止实例化。 */
  private PortDetector() {
  }

  /**
   * 探测进程树的主监听端口。
   *
   * @param rootPid 根进程 PID
   * @param debugPort JDWP 调试端口（需排除，可为 -1）
   * @param log 应用日志文件，用于裁决多候选端口（可为 null）
   * @return 主端口；探测不到返回 -1
   */
  static int detect(long rootPid, int debugPort, Path log) {
    Set<Integer> listening = listeningPorts(rootPid);
    if (listening.isEmpty()) return -1;
    return selectMainPort(listening, portFromLog(log, listening), debugPort);
  }

  /**
   * 从候选监听端口中选出主端口（纯函数，便于单测）。
   *
   * @param listening 进程监听到的端口集合
   * @param logPort 启动日志中框架自报的端口（可为 null）
   * @param debugPort JDWP 调试端口（可为 -1）
   * @return 主端口；全部候选均被过滤时返回 -1
   */
  static int selectMainPort(Set<Integer> listening, Integer logPort, int debugPort) {
    List<Integer> candidates = new ArrayList<Integer>();
    if (listening != null) {
      for (Integer candidate : listening) {
        if (candidate == null || candidate <= 0) continue;
        if (candidate == debugPort || isNoise(candidate)) continue;
        candidates.add(candidate);
      }
    }
    if (candidates.isEmpty()) return -1;
    if (logPort != null && candidates.contains(logPort)) return logPort;
    Collections.sort(candidates);
    return candidates.get(0);
  }

  /** 判断是否为已知的非业务噪声端口（DevTools LiveReload）。 */
  static boolean isNoise(int port) {
    return port == LIVERELOAD_PORT;
  }

  /**
   * 收集根进程及其全部后代的 PID，覆盖 setsid 包装、npm 脚本子进程等场景。
   *
   * @param rootPid 根进程 PID
   * @return PID 列表（根进程在前）
   */
  static List<Long> processTreePids(long rootPid) {
    Set<Long> pids = new LinkedHashSet<Long>();
    pids.add(rootPid);
    if (rootPid <= 0) return new ArrayList<Long>(pids);
    Map<Long, ProcessTree.Proc> table;
    try {
      table = ProcessTree.processTable();
    } catch (Throwable ignored) {
      return new ArrayList<Long>(pids);
    }
    for (ProcessTree.Proc proc : ProcessTree.descendants(table, rootPid)) {
      pids.add(proc.pid);
    }
    return new ArrayList<Long>(pids);
  }

  /**
   * 收集进程树中所有处于监听状态的 TCP 端口。
   *
   * @param rootPid 根进程 PID
   * @return 监听端口集合；平台不支持或权限不足时返回空集合
   */
  static Set<Integer> listeningPorts(long rootPid) {
    List<Long> pids = processTreePids(rootPid);
    String os = System.getProperty("os.name", "").toLowerCase();
    try {
      if (os.contains("win")) return listeningPortsFromNetstat(pids);
      if (os.contains("mac") || os.contains("darwin")) return listeningPortsFromLsof(pids);
      return listeningPortsFromProc(pids);
    } catch (Throwable ignored) {
      return new LinkedHashSet<Integer>();
    }
  }

  /**
   * Linux：读取 /proc/net/tcp{,6} 的监听套接字，再按 /proc/&lt;pid&gt;/fd 的 socket inode 归属过滤。
   *
   * @param pids 目标进程 PID 列表
   * @return 监听端口集合
   * @throws IOException 读取 procfs 失败
   */
  static Set<Integer> listeningPortsFromProc(List<Long> pids) throws IOException {
    Map<String, Integer> inodeToPort = new LinkedHashMap<String, Integer>();
    for (String table : new String[]{"/proc/net/tcp", "/proc/net/tcp6"}) {
      Path file = java.nio.file.Paths.get(table);
      if (!Files.isReadable(file)) continue;
      for (String line : Files.readAllLines(file, StandardCharsets.US_ASCII)) {
        String[] fields = line.trim().split("\\s+");
        // 字段：序号 本地地址:端口 远端地址:端口 状态 ... inode
        if (fields.length < 10 || !"0A".equals(fields[3])) continue;
        int separator = fields[1].lastIndexOf(':');
        if (separator < 0) continue;
        try {
          inodeToPort.put(fields[9], Integer.parseInt(fields[1].substring(separator + 1), 16));
        } catch (NumberFormatException ignored) {
        }
      }
    }
    Set<Integer> ports = new LinkedHashSet<Integer>();
    if (inodeToPort.isEmpty()) return ports;
    for (Long pid : pids) {
      Path fdDir = java.nio.file.Paths.get("/proc/" + pid + "/fd");
      if (!Files.isDirectory(fdDir)) continue;
      DirectoryStream<Path> stream = null;
      try {
        stream = Files.newDirectoryStream(fdDir);
        for (Path fd : stream) {
          String target;
          try {
            target = Files.readSymbolicLink(fd).toString();
          } catch (IOException ignored) {
            continue;
          }
          if (!target.startsWith("socket:[")) continue;
          String inode = target.substring("socket:[".length(), target.length() - 1);
          Integer port = inodeToPort.get(inode);
          if (port != null) ports.add(port);
        }
      } catch (IOException ignored) {
      } finally {
        if (stream != null) {
          try {
            stream.close();
          } catch (IOException ignored) {
          }
        }
      }
    }
    return ports;
  }

  /**
   * macOS：对每个进程执行 lsof 查询监听中的 TCP 端口。
   *
   * @param pids 目标进程 PID 列表
   * @return 监听端口集合
   */
  static Set<Integer> listeningPortsFromLsof(List<Long> pids) {
    Set<Integer> ports = new LinkedHashSet<Integer>();
    for (Long pid : pids) {
      String output = runAndCapture(PROBE_TIMEOUT_MS, "lsof", "-nP", "-a", "-p", Long.toString(pid), "-iTCP", "-sTCP:LISTEN", "-Fn");
      if (output == null) continue;
      for (String line : output.split("\\R")) {
        // lsof -Fn 输出形如 n*:9090 / n127.0.0.1:9090
        if (line.isEmpty() || line.charAt(0) != 'n') continue;
        int separator = line.lastIndexOf(':');
        if (separator < 0) continue;
        try {
          ports.add(Integer.parseInt(line.substring(separator + 1).trim()));
        } catch (NumberFormatException ignored) {
        }
      }
    }
    return ports;
  }

  /**
   * Windows：读取 netstat -ano 中属于目标进程的 LISTENING 端口。
   *
   * @param pids 目标进程 PID 列表
   * @return 监听端口集合
   */
  static Set<Integer> listeningPortsFromNetstat(List<Long> pids) {
    Set<Integer> ports = new LinkedHashSet<Integer>();
    Set<String> wanted = new LinkedHashSet<String>();
    for (Long pid : pids) wanted.add(Long.toString(pid));
    String output = runAndCapture(PROBE_TIMEOUT_MS, "netstat", "-ano");
    if (output == null) return ports;
    for (String line : output.split("\\R")) {
      String[] fields = line.trim().split("\\s+");
      // 字段：协议 本地地址:端口 远端地址:端口 状态 PID
      if (fields.length < 5 || !"LISTENING".equalsIgnoreCase(fields[3]) || !wanted.contains(fields[4])) continue;
      int separator = fields[1].lastIndexOf(':');
      if (separator < 0) continue;
      try {
        ports.add(Integer.parseInt(fields[1].substring(separator + 1)));
      } catch (NumberFormatException ignored) {
      }
    }
    return ports;
  }

  /**
   * 从日志尾部解析框架自报端口；仅采信落在实际监听集合内的端口，避免采信历史启动残留。
   *
   * @param log 应用日志文件（可为 null）
   * @param listening 进程当前监听端口集合
   * @return 日志中最近一次自报且确实在监听的端口；无匹配返回 null
   */
  static Integer portFromLog(Path log, Set<Integer> listening) {
    if (log == null || listening == null || listening.isEmpty() || !Files.isRegularFile(log)) return null;
    String content;
    try {
      content = SimpleIde.readLogTail(log, SimpleIde.LOG_TAIL_LIMIT_BYTES);
    } catch (IOException e) {
      return null;
    }
    if (content == null || content.isEmpty()) return null;
    for (Pattern pattern : LOG_PORT_PATTERNS) {
      Matcher matcher = pattern.matcher(content);
      Integer last = null;
      while (matcher.find()) {
        try {
          int port = Integer.parseInt(matcher.group(1));
          if (listening.contains(port)) last = port;
        } catch (NumberFormatException ignored) {
        }
      }
      if (last != null) return last;
    }
    return null;
  }

  /**
   * 执行外部探测命令并捕获标准输出。
   *
   * @param timeoutMs 超时毫秒数
   * @param argv 命令与参数
   * @return 标准输出内容；执行失败或超时返回 null
   */
  private static String runAndCapture(long timeoutMs, String... argv) {
    Process process = null;
    try {
      final Process started = new ProcessBuilder(argv).redirectErrorStream(false).start();
      process = started;
      started.getOutputStream().close();
      final StringBuilder output = new StringBuilder();
      Thread reader = new Thread(() -> {
        try (java.io.InputStream in = started.getInputStream()) {
          byte[] buffer = new byte[8192];
          int read;
          while ((read = in.read(buffer)) > 0) {
            output.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
          }
        } catch (IOException ignored) {
        }
      });
      reader.setDaemon(true);
      reader.start();
      if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        return null;
      }
      reader.join(200L);
      return output.toString();
    } catch (Exception e) {
      if (process != null) process.destroyForcibly();
      return null;
    } finally {
      if (process != null && process.isAlive()) process.destroyForcibly();
    }
  }
}
