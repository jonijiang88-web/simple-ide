package io.simpleide;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 构建生命周期跟踪器。
 *
 * <p>把构建的真实阶段、进程 PID、开始时间/耗时、日志路径与最终状态（成功/失败/超时/取消/未完整）
 * 持久化到 {@code .simple-ide/builds/}，供用户与代理查询；不伪造百分比或 ETA，
 * 长构建期间以心跳输出让调用方知道仍在推进。
 */
final class BuildTracker implements Closeable {

  /** 构建状态目录名。 */
  static final String BUILDS_DIR = ".simple-ide/builds";
  /** 默认心跳间隔（秒）。 */
  private static final int DEFAULT_HEARTBEAT_SEC = 30;
  /** 允许的阶段取值（用于稳定协议，避免各调用点各写各的字符串）。 */
  static final String PHASE_QUEUED = "QUEUED";
  static final String PHASE_DEPENDENCY_RESOLVE = "DEPENDENCY_RESOLVE";
  static final String PHASE_COMPILE_MAIN = "COMPILE_MAIN";
  static final String PHASE_COMPILE_TEST = "COMPILE_TEST";
  static final String PHASE_REBUILD = "REBUILD";
  static final String PHASE_FINISHED = "FINISHED";

  private final Path project;
  private final String buildId;
  private final Path statusFile;
  private final Path latestFile;
  private final Path logFile;
  private final long startedAt;
  private final Map<String, Object> state = new LinkedHashMap<String, Object>();
  private Thread heartbeat;
  private volatile boolean closed;

  private BuildTracker(Path project, String buildId, Path statusFile, Path latestFile, Path logFile, long startedAt) {
    this.project = project;
    this.buildId = buildId;
    this.statusFile = statusFile;
    this.latestFile = latestFile;
    this.logFile = logFile;
    this.startedAt = startedAt;
  }

  /** 生成一个新的构建 ID（与跟踪器内部规则一致，用于无跟踪器的场景）。 */
  static String newBuildId() {
    long now = System.currentTimeMillis();
    return new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date(now))
      + "-" + Long.toHexString(now % 0xFFFF);
  }

  /**
   * 开始跟踪一次构建。
   *
   * @param project 项目根目录
   * @param engine 构建引擎（jps / idea-mcp）
   * @param modules 目标模块
   * @param timeoutSec 超时秒数
   * @param clean 是否为清理重建
   * @return 跟踪器实例
   */
  static BuildTracker start(Path project, String engine, List<String> modules, int timeoutSec, boolean clean) {
    long now = System.currentTimeMillis();
    String buildId = newBuildId();
    Path dir = project.resolve(BUILDS_DIR);
    try {
      Files.createDirectories(dir);
    }
    catch (IOException ignored) {
    }
    Path statusFile = dir.resolve(buildId + ".json");
    Path latestFile = dir.resolve("latest.json");
    Path logFile = dir.resolve(buildId + ".log");
    BuildTracker tracker = new BuildTracker(project, buildId, statusFile, latestFile, logFile, now);
    tracker.state.put("buildId", buildId);
    tracker.state.put("projectPath", project.toAbsolutePath().toString());
    tracker.state.put("engine", engine);
    tracker.state.put("modules", modules == null ? new ArrayList<String>() : new ArrayList<String>(modules));
    tracker.state.put("clean", clean);
    tracker.state.put("timeoutSec", timeoutSec);
    tracker.state.put("state", "RUNNING");
    tracker.state.put("phase", PHASE_QUEUED);
    tracker.state.put("pid", -1L);
    tracker.state.put("startedAt", now);
    tracker.state.put("startedAtIso", iso(now));
    tracker.state.put("updatedAt", now);
    tracker.state.put("durationMs", 0L);
    tracker.state.put("logFile", logFile.toAbsolutePath().toString());
    tracker.state.put("toolVersion", SimpleIde.TOOL_VERSION);
    tracker.state.put("cacheFormatVersion", SimpleIde.CACHE_FORMAT_VERSION);
    tracker.persist();
    return tracker;
  }

  /** 构建 ID。 */
  String buildId() {
    return buildId;
  }

  /** 构建日志文件路径。 */
  Path logFile() {
    return logFile;
  }

  /** 状态文件路径。 */
  Path statusFile() {
    return statusFile;
  }

  /** 更新当前阶段并落盘。 */
  synchronized void phase(String phase) {
    state.put("phase", phase);
    touch();
  }

  /** 记录一条人类可读进度信息（同时写入日志）。 */
  synchronized void message(String message) {
    state.put("message", message);
    appendLog("[simple-ide] " + message + "\n");
    touch();
  }

  /** 记录子进程 PID。 */
  synchronized void pid(long pid) {
    state.put("pid", pid);
    touch();
  }

  /** 写入任意状态字段。 */
  synchronized void put(String key, Object value) {
    state.put(key, value);
    touch();
  }

  /** 追加构建日志（用于保留可追踪证据）。 */
  synchronized void appendLog(String text) {
    if (text == null || text.isEmpty()) return;
    try {
      AtomicFiles.append(logFile, text);
    }
    catch (IOException ignored) {
    }
  }

  /** 结束跟踪：落盘最终状态、退出码与错误码。 */
  synchronized void finish(String finalState, Integer exitCode, String errorCode) {
    long now = System.currentTimeMillis();
    state.put("state", finalState);
    state.put("phase", PHASE_FINISHED);
    if (exitCode != null) state.put("exitCode", exitCode);
    if (errorCode != null) state.put("errorCode", errorCode);
    state.put("finishedAt", now);
    state.put("finishedAtIso", iso(now));
    state.put("durationMs", now - startedAt);
    state.put("updatedAt", now);
    persist();
  }

  /** 当前状态快照。 */
  synchronized Map<String, Object> snapshot() {
    return new LinkedHashMap<String, Object>(state);
  }

  /**
   * 启动心跳线程，长构建期间定期向 stderr 输出进度，避免「长时间无输出=假死」的误判。
   *
   * @param printer 进度输出目标（通常为 System.err）
   */
  void startHeartbeat(final java.io.PrintStream printer) {
    int intervalSec = heartbeatIntervalSec();
    if (intervalSec <= 0) return;
    heartbeat = new Thread(new Runnable() {
      @Override
      public void run() {
        while (!closed) {
          try {
            Thread.sleep(intervalSec * 1000L);
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          }
          if (closed) return;
          Map<String, Object> snap = snapshot();
          long elapsedMs = System.currentTimeMillis() - startedAt;
          printer.println("[build " + buildId + "] 仍在执行 phase=" + snap.get("phase")
            + " elapsed=" + (elapsedMs / 1000) + "s log=" + logFile.toAbsolutePath());
          printer.flush();
        }
      }
    }, "simple-ide-build-heartbeat");
    heartbeat.setDaemon(true);
    heartbeat.start();
  }

  /** 关闭跟踪器并停止心跳。 */
  @Override
  public void close() {
    closed = true;
    Thread thread = heartbeat;
    if (thread != null) {
      thread.interrupt();
    }
  }

  /** 读取最近一次构建状态。 */
  static Map<String, Object> readLatest(Path project) {
    try {
      String content = AtomicFiles.read(project.resolve(BUILDS_DIR).resolve("latest.json"));
      if (content == null) return null;
      return JsonParser.tryParseObject(content);
    }
    catch (Exception e) {
      return null;
    }
  }

  /**
   * 读取指定构建状态。
   *
   * @param project 项目根目录
   * @param buildId 构建 ID，或 "latest"
   * @return 状态 Map；不存在时返回 null
   */
  static Map<String, Object> read(Path project, String buildId) {
    if (buildId == null || buildId.isEmpty() || "latest".equals(buildId)) {
      return readLatest(project);
    }
    try {
      String content = AtomicFiles.read(project.resolve(BUILDS_DIR).resolve(buildId + ".json"));
      if (content == null) return null;
      return JsonParser.tryParseObject(content);
    }
    catch (Exception e) {
      return null;
    }
  }

  /** 落盘当前状态（同时更新 latest.json）。 */
  private void persist() {
    state.put("updatedAt", System.currentTimeMillis());
    String content = SimpleIde.json(state);
    try {
      AtomicFiles.write(statusFile, content);
    }
    catch (IOException ignored) {
    }
    try {
      AtomicFiles.write(latestFile, content);
    }
    catch (IOException ignored) {
    }
  }

  /** 更新 updatedAt 并落盘。 */
  private void touch() {
    state.put("updatedAt", System.currentTimeMillis());
    persist();
  }

  /** 心跳间隔（秒），支持系统属性覆盖，0 表示关闭心跳。 */
  private static int heartbeatIntervalSec() {
    String value = System.getProperty("simple.ide.heartbeat.sec");
    if (value != null && !value.trim().isEmpty()) {
      try {
        return Integer.parseInt(value.trim());
      }
      catch (NumberFormatException ignored) {
      }
    }
    return DEFAULT_HEARTBEAT_SEC;
  }

  /** 格式化 ISO 时间。 */
  private static String iso(long millis) {
    return new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS").format(new java.util.Date(millis));
  }
}
