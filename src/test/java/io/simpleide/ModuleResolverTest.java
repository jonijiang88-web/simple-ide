package io.simpleide;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证多模块 Maven 模型解析、目标模块选择与作用域规范化。 */
public final class ModuleResolverTest {

  /** 创建一个两模块（lib + app）的临时 Maven 项目。 */
  private static Path createMultiModuleProject(Path dir) throws IOException {
    write(dir.resolve("pom.xml"),
      "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
        + "  <modelVersion>4.0.0</modelVersion>\n"
        + "  <groupId>com.example</groupId>\n"
        + "  <artifactId>root</artifactId>\n"
        + "  <version>1.0.0</version>\n"
        + "  <packaging>pom</packaging>\n"
        + "  <modules>\n    <module>lib</module>\n    <module>app</module>\n  </modules>\n"
        + "</project>\n");
    write(dir.resolve("lib/pom.xml"),
      "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
        + "  <modelVersion>4.0.0</modelVersion>\n"
        + "  <parent><groupId>com.example</groupId><artifactId>root</artifactId><version>1.0.0</version></parent>\n"
        + "  <artifactId>lib</artifactId>\n"
        + "  <build><outputDirectory>custom-out/lib-classes</outputDirectory></build>\n"
        + "</project>\n");
    write(dir.resolve("app/pom.xml"),
      "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
        + "  <modelVersion>4.0.0</modelVersion>\n"
        + "  <parent><groupId>com.example</groupId><artifactId>root</artifactId><version>1.0.0</version></parent>\n"
        + "  <artifactId>app</artifactId>\n"
        + "  <dependencies>\n    <dependency><groupId>com.example</groupId><artifactId>lib</artifactId><version>1.0.0</version></dependency>\n  </dependencies>\n"
        + "  <build>\n"
        + "    <outputDirectory>custom-out/app-classes</outputDirectory>\n"
        + "    <testOutputDirectory>custom-out/app-test-classes</testOutputDirectory>\n"
        + "  </build>\n"
        + "</project>\n");
    write(dir.resolve("app/src/main/java/com/example/App.java"), "package com.example;\npublic class App {}\n");
    write(dir.resolve("lib/src/main/java/com/example/Lib.java"), "package com.example;\npublic class Lib {}\n");
    return dir;
  }

  /** 验证多模块拓扑、自定义输出目录与模块依赖被正确解析。 */
  @Test
  void resolves_modules_topology_and_custom_outputs(@TempDir Path dir) throws Exception {
    createMultiModuleProject(dir);
    List<ModuleResolver.Module> modules = ModuleResolver.resolve(dir);

    // 验证：解析出 root/lib/app 三个模块
    assertEquals(3, modules.size());
    ModuleResolver.Module app = ModuleResolver.byName(modules, "app");
    ModuleResolver.Module lib = ModuleResolver.byName(modules, "lib");
    assertNotNull(app);
    assertNotNull(lib);
    // 验证：自定义主输出目录被解析
    assertEquals(dir.resolve("app/custom-out/app-classes"), app.outputDir);
    // 验证：自定义测试输出目录被解析
    assertEquals(dir.resolve("app/custom-out/app-test-classes"), app.testOutputDir);
    // 验证：模块间依赖被识别
    assertTrue(app.dependencyNames.contains("lib"));
    // 验证：识别出模块包含主源码
    assertTrue(app.hasMainSources());
  }

  /** 验证目标模块的依赖闭包按依赖顺序展开。 */
  @Test
  void computes_dependency_closure(@TempDir Path dir) throws Exception {
    createMultiModuleProject(dir);
    List<ModuleResolver.Module> modules = ModuleResolver.resolve(dir);
    ModuleResolver.Module app = ModuleResolver.byName(modules, "app");

    List<ModuleResolver.Module> closure = ModuleResolver.closure(modules, app);
    // 验证：闭包包含目标模块与其依赖模块
    assertEquals(2, closure.size());
    // 验证：目标模块排在首位
    assertEquals("app", closure.get(0).name);
    // 验证：依赖模块被包含
    assertEquals("lib", closure.get(1).name);
  }

  /** 验证多模块未指定目标时明确报错，而不是静默挑一个。 */
  @Test
  void rejects_ambiguous_target_when_multi_module(@TempDir Path dir) throws Exception {
    createMultiModuleProject(dir);
    List<ModuleResolver.Module> modules = ModuleResolver.resolve(dir);
    IOException error = assertThrows(IOException.class, () -> ModuleResolver.select(modules, null));
    // 验证：错误信息提示必须显式指定模块
    assertTrue(error.getMessage().contains("多模块"));
  }

  /** 验证 IntelliJ 模块名（带前缀）可映射到 artifactId。 */
  @Test
  void maps_intellij_module_name_to_artifactId(@TempDir Path dir) throws Exception {
    createMultiModuleProject(dir);
    List<ModuleResolver.Module> modules = ModuleResolver.resolve(dir);
    // 验证：artemis 风格的带前缀模块名可映射
    assertEquals("app", ModuleResolver.select(modules, "root.app").name);
    // 验证：精确 artifactId 也可映射
    assertEquals("app", ModuleResolver.select(modules, "app").name);
  }

  /** 验证未知模块名报错并列出可用模块。 */
  @Test
  void rejects_unknown_module(@TempDir Path dir) throws Exception {
    createMultiModuleProject(dir);
    List<ModuleResolver.Module> modules = ModuleResolver.resolve(dir);
    IOException error = assertThrows(IOException.class, () -> ModuleResolver.select(modules, "not-exist"));
    // 验证：错误信息包含可用模块列表
    assertTrue(error.getMessage().contains("可用模块"));
  }

  /** 验证单模块项目无需显式指定模块。 */
  @Test
  void single_module_project_needs_no_hint(@TempDir Path dir) throws Exception {
    write(dir.resolve("pom.xml"),
      "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
        + "  <modelVersion>4.0.0</modelVersion>\n  <artifactId>solo</artifactId>\n</project>\n");
    List<ModuleResolver.Module> modules = ModuleResolver.resolve(dir);
    // 验证：单模块可直接选中
    assertEquals("solo", ModuleResolver.select(modules, null).name);
  }

  /** 验证 resolveModuleScope 规范化模块名并区分未知/歧义。 */
  @Test
  void normalizes_module_scope(@TempDir Path dir) throws Exception {
    createMultiModuleProject(dir);
    SimpleIde.ModuleScope scope = SimpleIde.resolveModuleScope(dir, java.util.Arrays.asList("root.app"));
    // 验证：作用域被规范化为 artifactId
    assertEquals(java.util.Arrays.asList("app"), scope.names);
    assertNull(scope.error);

    SimpleIde.ModuleScope invalid = SimpleIde.resolveModuleScope(dir, java.util.Arrays.asList("ghost"));
    // 验证：未知模块返回明确错误
    assertNotNull(invalid.error);
    assertTrue(invalid.error.contains("ghost"));
  }

  /** 工具方法：写入文本文件（自动建目录）。 */
  private static void write(Path file, String content) throws IOException {
    Files.createDirectories(file.getParent());
    Files.write(file, content.getBytes(StandardCharsets.UTF_8));
  }
}
