package io.simpleide;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Spring Boot 热更新的最小契约：DevTools jar 可定位、classpath 检测准确、
 * trigger 文件创建后不会被重复启动覆盖。
 */
public final class HotReloadTest {

  /** 内置 DevTools jar 必须随分发存在，且文件名与约定一致。 */
  @Test
  public void findsBundledDevtoolsJar() {
    Path jar = SimpleIde.findDevtoolsJar();

    // 验证：分发内置 DevTools jar 可定位
    assertNotNull(jar);
    // 验证：jar 文件名与约定的 2.7.18 一致
    assertEquals(SimpleIde.DEVTOOLS_JAR_NAME, jar.getFileName().toString());
  }

  /** classpath 条目检测只按文件名匹配，不把项目路径误判为 Spring Boot 依赖。 */
  @Test
  public void detectsClasspathJarsByFileName() {
    String classpath = "/project/target/classes" + File.pathSeparator
      + "/repo/spring-boot-2.7.18.jar" + File.pathSeparator
      + "/repo/spring-boot-devtools-2.7.18.jar";

    // 验证：能识别 Spring Boot 主 jar
    assertTrue(SimpleIde.classpathContainsJar(classpath, "spring-boot-"));
    // 验证：能识别 DevTools jar
    assertTrue(SimpleIde.classpathContainsJar(classpath, "spring-boot-devtools"));
    // 验证：不会把普通目录误判为 jar
    assertFalse(SimpleIde.classpathContainsJar("/project/spring-boot-code/target/classes", "spring-boot-"));
  }

  /** trigger 文件首次创建后内容为空；再次确保存在时不得覆盖已有内容。 */
  @Test
  public void createsTriggerWithoutOverwriting(@TempDir Path project) throws Exception {
    Path trigger = SimpleIde.ensureHotReloadTrigger(project);

    // 验证：trigger 路径位于专用 devtools 目录
    assertEquals(project.resolve(SimpleIde.HOT_RELOAD_DIR).resolve(SimpleIde.HOT_RELOAD_TRIGGER_NAME), trigger);
    // 验证：首次创建为空文件
    assertEquals(0L, Files.size(trigger));

    Files.write(trigger, "reload".getBytes(StandardCharsets.UTF_8));
    SimpleIde.ensureHotReloadTrigger(project);

    // 验证：已存在的 trigger 不被重复创建覆盖
    assertEquals("reload", new String(Files.readAllBytes(trigger), StandardCharsets.UTF_8));
  }
}
