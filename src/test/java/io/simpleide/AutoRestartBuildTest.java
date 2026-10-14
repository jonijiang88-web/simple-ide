package io.simpleide;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证「运行中应用 + 待编译源文件」时构建改为停止应用 → 共享输出编译 → 按原参数重启的编排：
 * 待编译探测必须能识别源文件更新、重启命令行必须还原端口/调试端口/Java，避免改动只落到隔离目录。
 */
public final class AutoRestartBuildTest {

  /** 写入一个源文件并返回其路径。 */
  private static Path writeSource(Path project, String relative) throws Exception {
    Path file = project.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.write(file, "class A {}".getBytes(StandardCharsets.UTF_8));
    return file;
  }

  /** 写入编译产物并返回其路径。 */
  private static Path writeOutput(Path project, String relative) throws Exception {
    Path file = project.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.write(file, "compiled".getBytes(StandardCharsets.UTF_8));
    return file;
  }

  /** 【核心】源文件晚于编译产物时必须判定为待编译。 */
  @Test
  public void detectsPendingSourcesWhenSourceIsNewer(@TempDir Path project) throws Exception {
    Path output = writeOutput(project, "target/classes/A.class");
    Files.setLastModifiedTime(output, FileTime.fromMillis(1_000_000L));
    Path source = writeSource(project, "src/main/java/com/demo/A.java");
    Files.setLastModifiedTime(source, FileTime.fromMillis(2_000_000L));

    SimpleIde.PendingSources pending = SimpleIde.detectPendingSources(project);

    // 验证：源文件比产物新时应判定为存在待编译文件
    assertTrue(pending.dirty());
    assertEquals(1, pending.count);
    // 验证：提示中应给出最近改动的源文件
    assertEquals("src/main/java/com/demo/A.java", pending.newest);
  }

  /** 编译产物晚于源文件时不应判定为待编译，避免无改动构建白白中断应用。 */
  @Test
  public void skipsWhenOutputsAreNewerThanSources(@TempDir Path project) throws Exception {
    Path source = writeSource(project, "src/main/java/com/demo/A.java");
    Files.setLastModifiedTime(source, FileTime.fromMillis(1_000_000L));
    Path output = writeOutput(project, "target/classes/A.class");
    Files.setLastModifiedTime(output, FileTime.fromMillis(2_000_000L));

    SimpleIde.PendingSources pending = SimpleIde.detectPendingSources(project);

    // 验证：产物更新时不应判定为待编译
    assertFalse(pending.dirty());
    assertEquals(0, pending.count);
  }

  /** 没有任何编译产物时，源码一律视为需要编译。 */
  @Test
  public void treatsSourcesAsPendingWhenNeverCompiled(@TempDir Path project) throws Exception {
    writeSource(project, "src/main/java/com/demo/A.java");
    writeSource(project, "src/main/java/com/demo/B.java");

    SimpleIde.PendingSources pending = SimpleIde.detectPendingSources(project);

    // 验证：尚无编译产物时全部源文件都待编译
    assertTrue(pending.dirty());
    assertEquals(2, pending.count);
    assertFalse(pending.compiled);
  }

  /** 重启计划必须还原端口、调试端口与 Java 可执行文件。 */
  @Test
  public void buildsRestartPlanFromRunState(@TempDir Path project) throws Exception {
    Path runsDir = project.resolve(".simple-ide/runs");
    Files.createDirectories(runsDir);
    List<String> lines = new ArrayList<String>();
    lines.add("4242");
    lines.add(project.resolve(".simple-ide/runs/Application.log").toString());
    lines.add("19099");
    lines.add("41777");
    lines.add(project.toAbsolutePath().toString());
    lines.add("/usr/lib/jvm/java-8-openjdk-amd64/bin/java");
    lines.add("-cp");
    lines.add("classes");
    lines.add("com.demo.Main");
    lines.add("--server.port=19099");
    Files.write(runsDir.resolve("Application.properties"), lines, StandardCharsets.UTF_8);

    List<String> configurations = new ArrayList<String>();
    configurations.add("Application");
    List<Map<String, Object>> plan = SimpleIde.snapshotRestartPlan(project, configurations);

    // 验证：重启计划应还原配置名与端口
    assertEquals(1, plan.size());
    assertEquals("Application", plan.get(0).get("configuration"));
    assertEquals(19099, plan.get(0).get("port"));
    // 验证：调试端口与 Java 可执行文件也必须还原
    assertEquals(41777, plan.get(0).get("debugPort"));
    assertEquals("/usr/lib/jvm/java-8-openjdk-amd64/bin/java", plan.get(0).get("java"));
  }

  /** 重启命令行必须携带原端口、调试端口与 Java，并跳过前置编译。 */
  @Test
  public void restartCommandReusesRuntimeArguments(@TempDir Path project) throws Exception {
    Map<String, Object> item = new LinkedHashMap<String, Object>();
    item.put("configuration", "Application");
    item.put("port", Integer.valueOf(19099));
    item.put("debugPort", Integer.valueOf(41777));
    item.put("java", "/opt/java8/bin/java");

    List<String> argv = SimpleIde.restartCommand(project, item);

    // 验证：使用原 Java 可执行文件启动
    assertEquals("/opt/java8/bin/java", argv.get(0));
    // 验证：复用当前 simple-ide classpath 执行 run 子命令
    assertTrue(argv.contains("run"));
    assertTrue(argv.contains(SimpleIde.class.getName()));
    assertTrue(argv.contains("--skip-build"));
    // 验证：端口与调试端口按原值还原
    assertTrue(argv.contains("--port"));
    assertEquals("19099", argv.get(argv.indexOf("--port") + 1));
    assertEquals("41777", argv.get(argv.indexOf("--debug-port") + 1));
    // 验证：项目路径与配置名必须显式传入
    assertEquals(project.toAbsolutePath().toString(), argv.get(argv.indexOf("--project") + 1));
    assertEquals("Application", argv.get(argv.indexOf("--configuration") + 1));
    assertNotNull(argv.get(0));
  }

  /** 构建失败时只停未启，必须显式标记未重启，避免调用方误判应用仍在运行。 */
  @Test
  public void marksRestartSkippedWhenBuildFailed(@TempDir Path project) throws Exception {
    BuildTracker tracker = BuildTracker.start(project, "jps", new ArrayList<String>(), 60, false);
    try {
      tracker.put("autoRestart", Boolean.TRUE);
      tracker.put("stoppedApplications", java.util.Arrays.asList("Application"));
      Map<String, Object> result = new LinkedHashMap<String, Object>();

      SimpleIde.attachAutoRestartFields(result, tracker);

      // 验证：停止过应用但没有重启记录时必须显式标记
      assertEquals(Boolean.TRUE, result.get("restartSkippedBecauseBuildFailed"));
      // 验证：停止的应用列表必须原样透出
      assertEquals(java.util.Arrays.asList("Application"), result.get("stoppedApplications"));
    }
    finally {
      tracker.close();
    }
  }
}
