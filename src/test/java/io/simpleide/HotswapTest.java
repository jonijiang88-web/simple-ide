package io.simpleide;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 SIMPLE_IDE_HOTSWAP 的启用开关与工作目录契约。
 */
public final class HotswapTest {

  /** 系统属性可作为环境变量的测试替代开关。 */
  @Test
  public void enablesHotswapFromSystemProperty() {
    System.setProperty("simple.ide.hotswap", "true");
    try {
      // 验证：设置 simple.ide.hotswap=true 后启用 hotswap
      assertTrue(SimpleIde.hotswapEnabled());
    }
    finally {
      System.clearProperty("simple.ide.hotswap");
    }
  }

  /** 工作目录必须包含 agent 协议所需的 requests/classes/results。 */
  @Test
  public void createsAgentWorkspace(@TempDir Path project) throws Exception {
    SimpleIde.ensureHotswapWorkspace(project);

    // 验证：请求、字节码、结果目录均创建
    assertTrue(Files.isDirectory(project.resolve(SimpleIde.HOTSWAP_DIR).resolve("requests")));
    assertTrue(Files.isDirectory(project.resolve(SimpleIde.HOTSWAP_DIR).resolve("classes")));
    assertTrue(Files.isDirectory(project.resolve(SimpleIde.HOTSWAP_DIR).resolve("results")));
    // 验证：build 阶段标记已写入
    assertTrue(Files.isRegularFile(project.resolve(SimpleIde.HOTSWAP_DIR).resolve("build.json")));
    // 验证：运行标记路径按配置名区分
    assertEquals(project.resolve(SimpleIde.HOTSWAP_DIR).resolve("enabled-Application.json"),
      SimpleIde.hotswapMarker(project, "Application"));
  }
}
