package io.simpleide;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证构建状态跟踪与未完整构建标记（构建生命周期可查询、可追溯）。 */
public final class BuildTrackerTest {

  /** 验证构建状态被持久化并可查询到真实阶段与字段。 */
  @Test
  void persists_build_state(@TempDir Path project) throws Exception {
    BuildTracker tracker = BuildTracker.start(project, "jps", Arrays.asList("app"), 1800, false);
    String buildId = tracker.buildId();
    try {
      // 验证：初始阶段为排队中
      assertEquals(BuildTracker.PHASE_QUEUED, tracker.snapshot().get("phase"));
      // 验证：初始状态为运行中
      assertEquals("RUNNING", tracker.snapshot().get("state"));

      tracker.pid(4321);
      tracker.phase(BuildTracker.PHASE_COMPILE_MAIN);
      tracker.message("编译中");

      Map<String, Object> latest = BuildTracker.readLatest(project);
      assertNotNull(latest);
      // 验证：PID 被持久化
      assertEquals(4321L, latest.get("pid"));
      // 验证：阶段被持久化
      assertEquals(BuildTracker.PHASE_COMPILE_MAIN, latest.get("phase"));
      // 验证：日志路径被持久化
      assertNotNull(latest.get("logFile"));
      // 验证：工具版本与缓存格式版本可追溯
      assertEquals(SimpleIde.TOOL_VERSION, latest.get("toolVersion"));
      assertEquals(SimpleIde.CACHE_FORMAT_VERSION, latest.get("cacheFormatVersion"));
    }
    finally {
      tracker.finish("SUCCEEDED", 0, null);
      tracker.close();
    }

    Map<String, Object> byId = BuildTracker.read(project, buildId);
    assertNotNull(byId);
    // 验证：按构建 ID 可查询最终状态
    assertEquals("SUCCEEDED", byId.get("state"));
    // 验证：记录了退出码
    assertEquals(0L, byId.get("exitCode"));
    // 验证：耗时字段存在且非负
    assertTrue(((Number) byId.get("durationMs")).longValue() >= 0);
  }

  /** 验证失败状态记录 errorCode。 */
  @Test
  void records_failure_error_code(@TempDir Path project) throws Exception {
    BuildTracker tracker = BuildTracker.start(project, "jps", new java.util.ArrayList<String>(), 60, false);
    tracker.finish("FAILED", 1, "TIMEOUT");
    tracker.close();

    Map<String, Object> latest = BuildTracker.readLatest(project);
    // 验证：失败状态与错误码被记录
    assertEquals("FAILED", latest.get("state"));
    assertEquals("TIMEOUT", latest.get("errorCode"));
    assertEquals(1L, latest.get("exitCode"));
  }

  /** 验证构建日志被追加写入，作为可追踪证据。 */
  @Test
  void appends_build_log(@TempDir Path project) throws Exception {
    BuildTracker tracker = BuildTracker.start(project, "jps", new java.util.ArrayList<String>(), 60, false);
    tracker.appendLog("first line\n");
    tracker.appendLog("second line\n");
    Path logFile = tracker.logFile();
    tracker.finish("SUCCEEDED", 0, null);
    tracker.close();

    // 验证：日志文件存在且包含两行证据
    assertTrue(Files.isRegularFile(logFile));
    String content = new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8);
    assertTrue(content.contains("first line"));
    assertTrue(content.contains("second line"));
  }

  /** 验证未完整构建标记的写入与清理，保证下一次构建不把不完整缓存当有效。 */
  @Test
  void incomplete_marker_roundtrip(@TempDir Path project) throws Exception {
    Path marker = SimpleIde.incompleteBuildMarker(project);
    // 验证：初始不存在
    assertFalse(Files.exists(marker));

    SimpleIde.markIncompleteBuild(project, "build-1", "TIMEOUT");
    // 验证：标记文件被创建
    assertTrue(Files.exists(marker));
    String content = new String(Files.readAllBytes(marker), StandardCharsets.UTF_8);
    assertTrue(content.contains("TIMEOUT"));

    SimpleIde.clearIncompleteBuildMarker(project);
    // 验证：标记被清除
    assertFalse(Files.exists(marker));
  }

  /** 验证状态文件为原子写入，不会残留临时文件被误读。 */
  @Test
  void state_written_atomically(@TempDir Path project) throws Exception {
    BuildTracker tracker = BuildTracker.start(project, "jps", new java.util.ArrayList<String>(), 60, false);
    tracker.phase(BuildTracker.PHASE_COMPILE_TEST);
    tracker.finish("SUCCEEDED", 0, null);
    tracker.close();

    Path buildsDir = project.resolve(BuildTracker.BUILDS_DIR);
    File[] leftovers = buildsDir.toFile().listFiles((d, name) -> name.contains(".tmp-"));
    // 验证：没有残留临时文件
    assertTrue(leftovers == null || leftovers.length == 0);
    // 验证：latest.json 是合法 JSON
    assertNotNull(JsonParser.tryParseObject(
      new String(Files.readAllBytes(buildsDir.resolve("latest.json")), StandardCharsets.UTF_8)));
  }

  /** 验证不存在的构建记录返回 null。 */
  @Test
  void unknown_build_returns_null(@TempDir Path project) throws Exception {
    // 验证：未知构建 ID 查询返回 null
    assertNull(BuildTracker.read(project, "nope"));
  }
}
