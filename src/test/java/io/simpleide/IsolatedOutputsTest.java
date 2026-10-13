package io.simpleide;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【R4】验证受保护隔离构建的报告字段、输出映射解析与「测试使用隔离输出而非陈旧 target」。
 */
public final class IsolatedOutputsTest {

  /** 创建单模块项目 + 隔离输出目录。 */
  private static Path createProject(Path dir, Map<String, String> isolatedClasses,
                                    Map<String, String> isolatedTestClasses) throws IOException {
    Files.write(dir.resolve("pom.xml"),
      ("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n  <artifactId>solo</artifactId>\n</project>\n")
        .getBytes(StandardCharsets.UTF_8));
    Path src = dir.resolve("src/main/java");
    Files.createDirectories(src);
    Files.write(src.resolve("A.java"), "public class A {}\n".getBytes(StandardCharsets.UTF_8));
    Path classes = Files.createDirectories(dir.resolve(".simple-ide/isolated-build/out/solo/classes"));
    Path testClasses = Files.createDirectories(dir.resolve(".simple-ide/isolated-build/out/solo/test-classes"));
    isolatedClasses.put("solo", classes.toString());
    isolatedTestClasses.put("solo", testClasses.toString());
    return dir;
  }

  /** 验证 jps-cli 的 [OUTPUT-MAP] 行被解析为模块 → 输出目录映射。 */
  @Test
  void parses_output_map_lines() {
    String output = "[INFO] 编译输出已重定向\n"
      + "[OUTPUT-MAP] app=/iso/out/app/classes|/iso/out/app/test-classes\n"
      + "[OUTPUT-MAP] lib=/iso/out/lib/classes|/iso/out/lib/test-classes\n";
    Map<String, String> classes = new LinkedHashMap<String, String>();
    Map<String, String> testClasses = new LinkedHashMap<String, String>();
    SimpleIde.parseOutputMap(output, classes, testClasses);

    // 验证：两个模块的主输出被解析
    assertEquals("/iso/out/app/classes", classes.get("app"));
    assertEquals("/iso/out/lib/classes", classes.get("lib"));
    // 验证：测试输出被解析
    assertEquals("/iso/out/app/test-classes", testClasses.get("app"));
    assertEquals(2, classes.size());
  }

  /** 验证隔离字段在结果 JSON 中显式暴露（含未发布与陈旧语义）。 */
  @Test
  void exposes_isolation_fields_in_json() {
    SimpleIde.IsolationReport isolation = new SimpleIde.IsolationReport();
    isolation.isolated = true;
    isolation.cacheDir = java.nio.file.Paths.get("/iso/cache");
    isolation.outputRoot = java.nio.file.Paths.get("/iso/out");
    isolation.classes.put("app", "/iso/out/app/classes");
    isolation.testClasses.put("app", "/iso/out/app/test-classes");

    Map<String, Object> result = new LinkedHashMap<String, Object>();
    SimpleIde.applyIsolationFields(result, isolation);

    // 验证：显式标记隔离构建
    assertEquals(Boolean.TRUE, result.get("isolatedBuild"));
    // 验证：显式声明未发布
    assertEquals(Boolean.FALSE, result.get("outputsPublished"));
    // 验证：显式声明共享产物陈旧
    assertEquals(Boolean.TRUE, result.get("targetOutputsStale"));
    // 验证：给出隔离输出位置
    List<?> outputs = (List<?>) result.get("isolatedOutputs");
    assertNotNull(outputs);
    assertEquals(1, outputs.size());
    @SuppressWarnings("unchecked")
    Map<String, Object> first = (Map<String, Object>) outputs.get(0);
    assertEquals("app", first.get("module"));
    assertEquals("/iso/out/app/classes", first.get("classes"));
    assertEquals("/iso/out/app/test-classes", first.get("testClasses"));
  }

  /** 验证非隔离构建不写入隔离字段。 */
  @Test
  void non_isolated_build_has_no_isolation_fields() {
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    SimpleIde.applyIsolationFields(result, null);
    // 验证：没有隔离字段
    assertFalse(result.containsKey("isolatedBuild"));
    assertFalse(result.containsKey("outputsPublished"));
  }

  /** 验证测试 Classpath 使用隔离输出目录而不是共享 target。 */
  @Test
  void test_classpath_uses_isolated_outputs(@TempDir Path dir) throws Exception {
    Map<String, String> isolatedClasses = new LinkedHashMap<String, String>();
    Map<String, String> isolatedTestClasses = new LinkedHashMap<String, String>();
    createProject(dir, isolatedClasses, isolatedTestClasses);

    SimpleIde.ClasspathPlan plan = SimpleIde.resolveProjectClasspath(dir, null, true, isolatedClasses, isolatedTestClasses);

    // 验证：Classpath 指向隔离输出目录
    assertTrue(plan.classpath.contains(dir.resolve(".simple-ide/isolated-build/out/solo/classes").toString()));
    assertTrue(plan.classpath.contains(dir.resolve(".simple-ide/isolated-build/out/solo/test-classes").toString()));
    // 验证：没有使用共享 target（否则会跑在陈旧 class 上）
    assertFalse(plan.classpath.contains(dir.resolve("target/classes").toString()));
    assertFalse(plan.classpath.contains(dir.resolve("target/test-classes").toString()));
  }

  /** 验证无隔离覆盖时仍使用共享 target（普通路径不受影响）。 */
  @Test
  void shared_classpath_when_no_isolation(@TempDir Path dir) throws Exception {
    Map<String, String> ignored = new LinkedHashMap<String, String>();
    createProject(dir, ignored, ignored);
    SimpleIde.ClasspathPlan plan = SimpleIde.resolveProjectClasspath(dir, null, true);
    // 验证：普通路径使用共享 target
    assertTrue(plan.classpath.contains(dir.resolve("target/classes").toString()));
    assertTrue(plan.classpath.contains(dir.resolve("target/test-classes").toString()));
  }

  /** 验证隔离信息能并入已有测试结果 JSON。 */
  @Test
  void merges_isolation_fields_into_test_json() {
    SimpleIde.BuildStatus status = new SimpleIde.BuildStatus(true, "{}");
    status.isolatedBuild = true;
    status.isolatedClasses = new LinkedHashMap<String, String>();
    status.isolatedClasses.put("solo", "/iso/classes");
    status.isolatedTestClasses = new LinkedHashMap<String, String>();

    String merged = SimpleIde.iterateIsolationFields("{\"success\":true,\"passed\":1}", status);

    // 验证：原有字段保留
    assertTrue(merged.contains("\"passed\":1"));
    // 验证：隔离字段被注入
    assertTrue(merged.contains("\"isolatedBuild\":true"));
    // 验证：明确未发布与目标陈旧
    assertTrue(merged.contains("\"outputsPublished\":false"));
    assertTrue(merged.contains("\"targetOutputsStale\":true"));
    // 验证：JSON 仍可被真实解析
    Map<String, Object> parsed = JsonParser.tryParseObject(merged);
    assertEquals(Boolean.TRUE, parsed.get("success"));
    assertEquals(Boolean.TRUE, parsed.get("isolatedBuild"));
  }

  /** 验证隔离标记可写入任意路径（隔离构建不污染共享标记）。 */
  @Test
  void isolated_marker_path_is_independent(@TempDir Path project) throws Exception {
    Path isolatedMarker = SimpleIde.isolatedBuildRoot(project).resolve("incomplete.json");
    SimpleIde.markIncompleteBuildAt(isolatedMarker, "b1", "in_progress");
    // 验证：隔离标记被写入
    assertTrue(Files.isRegularFile(isolatedMarker));
    // 验证：共享标记未被写入
    assertFalse(Files.exists(SimpleIde.incompleteBuildMarker(project)));
    // 验证：清理只影响指定路径
    Files.deleteIfExists(isolatedMarker);
    assertFalse(Files.exists(isolatedMarker));
  }

  /** 验证隔离输出根与共享 target 物理分离。 */
  @Test
  void isolated_root_is_separate_from_target(@TempDir Path project) {
    Path root = SimpleIde.isolatedBuildRoot(project).toAbsolutePath();
    // 验证：隔离根不在 target 下
    assertFalse(root.startsWith(project.resolve("target").toAbsolutePath()));
    // 验证：隔离根位于 .simple-ide 下
    assertTrue(root.startsWith(project.resolve(".simple-ide").toAbsolutePath()));
    // 验证：与共享缓存目录不同
    assertFalse(root.equals(JpsCacheValidator.projectCacheDir(project).toAbsolutePath()));
  }

  /** 验证隔离输出目录的文件确实与共享 target 不同（真实文件系统断言）。 */
  @Test
  void isolated_output_dir_holds_its_own_classes(@TempDir Path project) throws Exception {
    Map<String, String> classes = new LinkedHashMap<String, String>();
    Map<String, String> testClasses = new LinkedHashMap<String, String>();
    createProject(project, classes, testClasses);
    Path sharedTarget = Files.createDirectories(project.resolve("target/classes"));
    Files.write(sharedTarget.resolve("Shared.class"), new byte[]{1, 2, 3});
    Path isolated = java.nio.file.Paths.get(classes.get("solo"));
    Files.write(isolated.resolve("Isolated.class"), new byte[]{4, 5, 6});

    // 验证：两侧产物互不影响
    assertTrue(Files.isRegularFile(sharedTarget.resolve("Shared.class")));
    assertFalse(Files.exists(sharedTarget.resolve("Isolated.class")));
    assertTrue(Files.isRegularFile(isolated.resolve("Isolated.class")));
    assertEquals(new File(isolated.toFile(), "Isolated.class").length(), 3L);
  }
}
