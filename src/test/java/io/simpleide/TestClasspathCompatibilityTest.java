package io.simpleide;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 在独立 JVM 中验证旧版项目 JUnit 与内置测试运行器的兼容性。 */
class TestClasspathCompatibilityTest {
  /** 验证原始拼接顺序确实会触发旧版 commons 缺方法的问题。 */
  @Test
  void reproduces_mixed_junit_failure() {
    String classpath = legacyClasspath() + File.pathSeparator
      + System.getProperty("simple.ide.test.runtime-classpath");
    ProcessRunner.Result result = runFixture(classpath, "PassingFixtureTest");
    // 验证：旧版 JUnit 排在新版运行器前面会导致测试进程失败。
    assertTrue(result.exitCode != 0, result.stdout + result.stderr);
    // 验证：失败来自与 aiworker 相同的新版运行器调用旧版 commons 方法。
    assertTrue((result.stdout + result.stderr).contains("forEachInReverseOrder"));
  }

  /** 验证旧版项目依赖被替换后，普通 JUnit 5 测试可以正常执行。 */
  @Test
  void runs_junit5_with_legacy_project_dependencies() {
    assertFixturePasses("PassingFixtureTest", 2);
  }

  /** 验证参数化测试依赖也与运行器统一版本，并执行全部参数。 */
  @Test
  void runs_parameterized_tests_with_legacy_project_dependencies() {
    assertFixturePasses("ParameterizedFixtureTest", 2);
  }

  /** 验证统一 JUnit 版本后仍兼容通过 Vintage 执行的 JUnit 4 测试。 */
  @Test
  void preserves_junit4_support() {
    assertFixturePasses("JUnit4PassingFixtureTest", 1);
  }

  /** 验证业务依赖、输出目录和第三方测试扩展不会被删除。 */
  @Test
  void retains_project_dependencies_and_custom_engines() {
    String project = String.join(File.pathSeparator, "target/test-classes", "business-1.8.2.jar",
      "custom-engine-1.0.jar", "junit-platform-commons-1.8.2.jar");
    String actual = SimpleIde.buildTestClasspath(project,
      Arrays.asList(Paths.get("junit-platform-commons-1.11.4.jar")));
    // 验证：只替换内置运行器提供的同名 JUnit 组件。
    assertEquals(String.join(File.pathSeparator, "target/test-classes", "business-1.8.2.jar",
      "custom-engine-1.0.jar", "junit-platform-commons-1.11.4.jar"), actual);
  }

  /** 使用旧版项目类路径和内置运行器执行夹具并核验真实执行数量。 */
  private void assertFixturePasses(String fixture, int count) {
    List<Path> runtime = new ArrayList<Path>();
    for (String entry : System.getProperty("simple.ide.test.runtime-classpath")
      .split(Pattern.quote(File.pathSeparator))) runtime.add(Paths.get(entry));
    String classpath = SimpleIde.buildTestClasspath(legacyClasspath(), runtime);
    // 验证：测试子进程不再携带旧版 Platform commons。
    assertFalse(classpath.contains("junit-platform-commons-1.8.2.jar"));
    // 验证：参数化测试组件也不再使用项目旧版。
    assertFalse(classpath.contains("junit-jupiter-params-5.8.2.jar"));
    ProcessRunner.Result result = runFixture(classpath, fixture);
    // 验证：测试进程正常退出，避免仅验证类路径字符串却无法运行。
    assertEquals(0, result.exitCode, result.stdout + result.stderr);
    Map<String, Object> report = JsonParser.parseObject(result.stdout.trim());
    // 验证：报告确认测试成功。
    assertEquals(Boolean.TRUE, report.get("success"));
    // 验证：测试实际执行了预期数量的用例。
    assertEquals(count, ((Number) report.get("executed")).intValue());
  }

  /** 返回独立解析的旧版 JUnit 项目依赖类路径。 */
  private String legacyClasspath() {
    return System.getProperty("simple.ide.test.legacy-junit-classpath");
  }

  /** 在独立 JVM 中执行测试夹具，避免 Gradle 自身类加载器掩盖版本冲突。 */
  private ProcessRunner.Result runFixture(String classpath, String fixture) {
    String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
    return ProcessRunner.run(Arrays.asList(java, "-cp", classpath, "io.simpleide.LocalTestRunner",
      "io.simpleide.fixtures." + fixture), Paths.get("."), 30, false,
      ProcessRunner.DEFAULT_MAX_OUTPUT_BYTES, null, null);
  }
}
