package io.simpleide;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证未完整构建标记的所有权语义、运行中应用探测与残留 JPS 构建检测。
 *
 * <p>这些是「清理共享输出必须经过同一保护」与「父命令被强杀后不并发写同一缓存」的基础能力。
 */
public final class IncompleteBuildGuardTest {

  /** 验证标记只在属于本次构建时才被清除，避免误删更新构建的标记。 */
  @Test
  void marker_clear_respects_ownership(@TempDir Path project) throws Exception {
    Path marker = SimpleIde.incompleteBuildMarker(project);

    SimpleIde.markIncompleteBuild(project, "build-A", "in_progress");
    // 验证：标记已建立
    assertTrue(Files.exists(marker));
    // 验证：非本次构建不删除标记
    SimpleIde.clearIncompleteBuildMarkerIfOwned(project, "build-B");
    assertTrue(Files.exists(marker), "不应删除其它构建的标记");
    // 验证：属于本次构建时删除
    SimpleIde.clearIncompleteBuildMarkerIfOwned(project, "build-A");
    assertFalse(Files.exists(marker));
  }

  /** 验证标记在构建启动前即可建立（不依赖 tracker 是否存在）。 */
  @Test
  void marker_can_be_written_without_tracker(@TempDir Path project) throws Exception {
    // 验证：无 tracker 场景（run/test 的隐式前置构建）也能可靠建立标记
    SimpleIde.markIncompleteBuild(project, BuildTracker.newBuildId(), "in_progress");
    assertTrue(Files.exists(SimpleIde.incompleteBuildMarker(project)));
    String content = new String(Files.readAllBytes(SimpleIde.incompleteBuildMarker(project)), StandardCharsets.UTF_8);
    // 验证：标记包含构建 ID 与原因，便于追溯
    assertTrue(content.contains("buildId"));
    assertTrue(content.contains("in_progress"));
  }

  /** 验证运行中应用探测：存活的 PID 才会被计入。 */
  @Test
  void detects_running_configurations(@TempDir Path project) throws Exception {
    Path runsDir = Files.createDirectories(project.resolve(".simple-ide/runs"));
    Process live = new ProcessBuilder("sleep", "30").redirectErrorStream(true).start();
    try {
      Files.write(runsDir.resolve("Application.properties"),
        (ProcessTree.pidOf(live) + "\n/tmp/app.log\n18080\n-1\n" + project + "\n")
          .getBytes(StandardCharsets.UTF_8));
      List<String> running = SimpleIde.runningConfigurations(project);
      // 验证：存活进程被识别为运行中应用（清理共享输出必须被阻止）
      assertTrue(running.contains("Application"), "应识别运行中的应用: " + running);
    }
    finally {
      live.destroyForcibly();
      live.waitFor();
    }
  }

  /** 验证不存在存活进程时不误报运行中应用。 */
  @Test
  void stopped_process_is_not_running(@TempDir Path project) throws Exception {
    Path runsDir = Files.createDirectories(project.resolve(".simple-ide/runs"));
    Files.write(runsDir.resolve("Application.properties"),
      "999999\n/tmp/app.log\n18080\n-1\n".getBytes(StandardCharsets.UTF_8));
    // 验证：不存在的 PID 不算运行中
    assertTrue(SimpleIde.runningConfigurations(project).isEmpty());
  }

  /** 验证残留 JPS 构建检测：构建锁记录的存活 PID 会被识别为残留构建。 */
  @Test
  void detects_orphan_build_from_lock_file(@TempDir Path project) throws Exception {
    Process holder = new ProcessBuilder("sleep", "30").redirectErrorStream(true).start();
    try {
      Files.createDirectories(project.resolve(".simple-ide"));
      Files.write(project.resolve(".simple-ide/jps-build.lock"),
        ("PID: " + ProcessTree.pidOf(holder) + ", startedAt: 0").getBytes(StandardCharsets.UTF_8));

      List<Long> orphans = SimpleIde.detectOrphanJpsBuilds(project, null);
      // 验证：锁文件记录的存活进程被识别为残留构建（阻断新构建并发写同一缓存）
      assertTrue(orphans.contains(ProcessTree.pidOf(holder)), "应识别残留构建: " + orphans);
    }
    finally {
      holder.destroyForcibly();
      holder.waitFor();
    }
  }

  /** 验证无残留时不误报。 */
  @Test
  void no_orphan_when_nothing_running(@TempDir Path project) throws Exception {
    List<Long> orphans = SimpleIde.detectOrphanJpsBuilds(project, null);
    // 验证：没有残留构建时返回空列表
    assertTrue(orphans.isEmpty());
  }

  /** 验证模块作用域解析失败时给出明确错误（而非静默构建别的目标）。 */
  @Test
  void module_scope_error_is_explicit(@TempDir Path project) throws Exception {
    Files.write(project.resolve("pom.xml"),
      ("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n  <artifactId>solo</artifactId>\n</project>\n")
        .getBytes(StandardCharsets.UTF_8));
    SimpleIde.ModuleScope scope = SimpleIde.resolveModuleScope(project, java.util.Arrays.asList("ghost"));
    // 验证：未知模块给出显式错误
    assertNotNull(scope.error);
    // 验证：错误信息包含模块名
    assertTrue(scope.error.contains("ghost"));
    // 验证：有效模块返回规范化名
    assertNull(SimpleIde.resolveModuleScope(project, java.util.Arrays.asList("solo")).error);
  }

  /** 验证 build-status 在运行期间能读取到 JPS PID（启动回调登记）。 */
  @Test
  void build_status_reports_live_pid(@TempDir Path project) throws Exception {
    BuildTracker tracker = BuildTracker.start(project, "jps", new java.util.ArrayList<String>(), 60, false);
    Process child = new ProcessBuilder("sleep", "30").redirectErrorStream(true).start();
    try {
      // 模拟启动回调：进程创建后立即登记 PID（修复前要等 run() 返回才有 PID）
      tracker.pid(ProcessTree.pidOf(child));
      tracker.put("jpsPid", ProcessTree.pidOf(child));

      java.util.Map<String, Object> latest = BuildTracker.readLatest(project);
      assertNotNull(latest);
      // 验证：运行期间即可查到存活 JPS PID
      assertEquals(ProcessTree.pidOf(child), ((Number) latest.get("jpsPid")).longValue());
      // 验证：状态仍为 RUNNING
      assertEquals("RUNNING", latest.get("state"));
    }
    finally {
      child.destroyForcibly();
      child.waitFor();
      tracker.finish("SUCCEEDED", 0, null);
      tracker.close();
    }
  }

  /** 验证探测需要真实存活进程（防止「状态文件存在即视为运行」）。 */
  @Test
  void dead_lock_pid_is_not_orphan(@TempDir Path project) throws Exception {
    Files.createDirectories(project.resolve(".simple-ide"));
    Files.write(project.resolve(".simple-ide/jps-build.lock"),
      "PID: 999999, startedAt: 0".getBytes(StandardCharsets.UTF_8));
    // 验证：不存在的 PID 不构成残留构建
    assertTrue(SimpleIde.detectOrphanJpsBuilds(project, null).isEmpty());
  }
}
