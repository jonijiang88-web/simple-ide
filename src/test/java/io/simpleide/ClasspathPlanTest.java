package io.simpleide;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证按模块解析 Classpath：精确输出目录、逐模块依赖缓存与残缺缓存告警。 */
public final class ClasspathPlanTest {

  /** 验证多模块项目按目标模块选择输出目录与依赖，而不是固定根目录 target/classes。 */
  @Test
  void selects_module_specific_outputs_and_dependencies(@TempDir Path dir) throws Exception {
    Path jar = writeJar(dir.resolve("repo/lib-1.0.jar"));
    createMultiModuleProject(dir, jar);

    SimpleIde.ClasspathPlan plan = SimpleIde.resolveProjectClasspath(dir, "app", true);

    // 验证：命中目标模块
    assertEquals("app", plan.moduleName);
    // 验证：使用目标模块的自定义测试输出目录
    assertTrue(plan.classpath.contains(dir.resolve("app/custom-out/app-test-classes").toString()));
    // 验证：使用目标模块的自定义主输出目录
    assertTrue(plan.classpath.contains(dir.resolve("app/custom-out/app-classes").toString()));
    // 验证：包含依赖模块的输出目录
    assertTrue(plan.classpath.contains(dir.resolve("lib/custom-out/lib-classes").toString()));
    // 验证：包含模块依赖缓存的 Jar
    assertTrue(plan.classpath.contains(jar.toString()));
    // 验证：没有误用根目录固定 target/classes
    assertFalse(plan.classpath.contains(dir.resolve("target/classes").toString()));
    // 验证：无告警
    assertTrue(plan.warnings.isEmpty(), "不应有告警: " + plan.warnings);
  }

  /** 验证多模块未指定模块时明确报错，而不是静默取第一个缓存。 */
  @Test
  void rejects_ambiguous_module_selection(@TempDir Path dir) throws Exception {
    Path jar = writeJar(dir.resolve("repo/lib-1.0.jar"));
    createMultiModuleProject(dir, jar);
    IOException error = assertThrows(IOException.class, () -> SimpleIde.resolveProjectClasspath(dir, null, false));
    // 验证：错误信息提示需要显式指定模块
    assertTrue(error.getMessage().contains("多模块"));
  }

  /** 验证依赖缓存中缺失的 Jar 会被显式告警，而不是静默接受残缺缓存。 */
  @Test
  void warns_when_cached_jar_is_missing(@TempDir Path dir) throws Exception {
    Path missingJar = dir.resolve("repo/gone-1.0.jar");
    Path existingJar = writeJar(dir.resolve("repo/lib-1.0.jar"));
    createMultiModuleProject(dir, missingJar);
    // lib 模块缓存指向真实存在的 Jar，只有 app 模块缓存引用缺失 Jar
    Files.write(dir.resolve(".jps-deps/classpath-lib.cache"), (existingJar + "\n").getBytes(StandardCharsets.UTF_8));

    SimpleIde.ClasspathPlan plan = SimpleIde.resolveProjectClasspath(dir, "app", false);

    // 验证：缺失 Jar 未被静默接受，产生了告警
    assertEquals(1, plan.warnings.size());
    assertTrue(plan.warnings.get(0).contains("不完整"));
    // 验证：缺失 Jar 不会进入 Classpath
    assertFalse(plan.classpath.contains(missingJar.toString()));
  }

  /** 验证单模块项目仍可回退到唯一缓存（保持历史兼容）。 */
  @Test
  void single_module_project_uses_unique_cache(@TempDir Path dir) throws Exception {
    Path jar = writeJar(dir.resolve("repo/solo-1.0.jar"));
    Files.createDirectories(dir.resolve("src/main/java"));
    Files.write(dir.resolve("pom.xml"),
      ("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
        + "  <modelVersion>4.0.0</modelVersion>\n  <artifactId>solo</artifactId>\n</project>\n")
        .getBytes(StandardCharsets.UTF_8));
    Path cacheDir = Files.createDirectories(dir.resolve(".jps-deps"));
    Files.write(cacheDir.resolve("classpath-solo.cache"), (jar + "\n").getBytes(StandardCharsets.UTF_8));

    SimpleIde.ClasspathPlan plan = SimpleIde.resolveProjectClasspath(dir, null, false);

    // 验证：单模块项目自动选中
    assertEquals("solo", plan.moduleName);
    // 验证：唯一缓存中的依赖被纳入
    assertTrue(plan.classpath.contains(jar.toString()));
    // 验证：无告警
    assertTrue(plan.warnings.isEmpty());
  }

  /** 验证按模块精确匹配缓存文件，多个候选且未指定模块时返回 null。 */
  @Test
  void find_classpath_cache_requires_unique_match(@TempDir Path dir) throws Exception {
    Path cacheDir = Files.createDirectories(dir.resolve(".jps-deps"));
    Files.write(cacheDir.resolve("classpath-app.cache"), "a\n".getBytes(StandardCharsets.UTF_8));
    Files.write(cacheDir.resolve("classpath-lib.cache"), "b\n".getBytes(StandardCharsets.UTF_8));

    // 验证：未指定模块且存在多个候选时返回 null，避免静默取第一个
    assertEquals(null, SimpleIde.findClasspathCacheFile(dir, null));
    // 验证：指定模块时精确命中
    assertEquals(cacheDir.resolve("classpath-app.cache").toFile(), SimpleIde.findClasspathCacheFile(dir, "app"));
    // 验证：指定不存在的模块时不回退到其它模块缓存
    assertEquals(null, SimpleIde.findClasspathCacheFile(dir, "ghost"));
  }

  /** 创建两模块项目，并把依赖缓存指向给定 Jar。 */
  private static void createMultiModuleProject(Path dir, Path jar) throws IOException {
    write(dir.resolve("pom.xml"),
      "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
        + "  <modelVersion>4.0.0</modelVersion>\n  <artifactId>root</artifactId>\n"
        + "  <packaging>pom</packaging>\n  <modules>\n    <module>lib</module>\n    <module>app</module>\n  </modules>\n"
        + "</project>\n");
    write(dir.resolve("lib/pom.xml"),
      "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
        + "  <modelVersion>4.0.0</modelVersion>\n  <artifactId>lib</artifactId>\n"
        + "  <build><outputDirectory>custom-out/lib-classes</outputDirectory></build>\n</project>\n");
    write(dir.resolve("app/pom.xml"),
      "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
        + "  <modelVersion>4.0.0</modelVersion>\n  <artifactId>app</artifactId>\n"
        + "  <dependencies><dependency><groupId>com.example</groupId><artifactId>lib</artifactId><version>1.0.0</version></dependency></dependencies>\n"
        + "  <build><outputDirectory>custom-out/app-classes</outputDirectory>"
        + "<testOutputDirectory>custom-out/app-test-classes</testOutputDirectory></build>\n</project>\n");
    write(dir.resolve("app/src/main/java/com/example/App.java"), "package com.example;\npublic class App {}\n");
    write(dir.resolve("lib/src/main/java/com/example/Lib.java"), "package com.example;\npublic class Lib {}\n");
    Files.createDirectories(dir.resolve(".jps-deps"));
    Files.write(dir.resolve(".jps-deps/classpath-app.cache"), (jar + "\n").getBytes(StandardCharsets.UTF_8));
    Files.write(dir.resolve(".jps-deps/classpath-lib.cache"), (jar + "\n").getBytes(StandardCharsets.UTF_8));
  }

  /** 工具方法：创建占位 Jar 文件。 */
  private static Path writeJar(Path file) throws IOException {
    Files.createDirectories(file.getParent());
    Files.write(file, new byte[]{0x50, 0x4b, 0x03, 0x04});
    return file;
  }

  /** 工具方法：写入文本文件。 */
  private static void write(Path file, String content) throws IOException {
    Files.createDirectories(file.getParent());
    Files.write(file, content.getBytes(StandardCharsets.UTF_8));
  }
}
