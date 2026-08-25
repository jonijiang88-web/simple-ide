package io.simpleide;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证命令行入口的最小解析能力。 */
public final class SimpleIdeTest {
  /** 验证命令入口类可以被正常加载。 */
  @Test
  void loads_cli_entrypoint() {
    // 验证：CLI 类名保持稳定，供发行脚本调用。
    assertEquals("io.simpleide.SimpleIde", SimpleIde.class.getName());
  }

  /** 验证 CLI 分配的随机端口可被后续应用进程绑定。 */
  @Test
  void allocates_available_port() throws Exception {
    int port = SimpleIde.findAvailablePort();

    // 验证：系统分配的端口位于有效 TCP 范围。
    assertTrue(port > 0 && port <= 65535);
    // 验证：刚释放的端口尚未被监听。
    assertFalse(SimpleIde.isPortOpen(port));
  }

  /** 验证日志查询只保留最新的指定行数。 */
  @Test
  void returns_log_tail() throws Exception {
    var file = Files.createTempFile("simple-ide", ".log");
    Files.writeString(file, "first\nsecond\nthird\n");

    // 验证：日志尾部保留最新两行。
    assertEquals(java.util.List.of("second", "third"), SimpleIde.tail(file, 2));
  }

  /** 验证帮助信息包含随机端口和日志查询入口。 */
  @Test
  void describes_runtime_commands() {
    // 验证：运行帮助说明随机端口参数。
    assertTrue(SimpleIde.helpText("run").contains("--random-port"));
    // 验证：构建帮助说明缓存异常的受控全量重建参数。
    assertTrue(SimpleIde.helpText("build").contains("--auto-rebuild"));
    // 验证：顶层帮助列出日志查询命令。
    assertTrue(SimpleIde.helpText(null).contains("logs"));
  }

  /** 验证仅对可识别的增量编译缓存异常请求全量重建。 */
  @Test
  void identifies_recoverable_incremental_build_failures() {
    // 验证：MapStruct 读取历史匿名内部类产物时可触发一次全量重建。
    assertTrue(SimpleIde.isRecoverableIncrementalBuildFailure(List.of("error: MapStruct cannot access ApplicationService$10.class")));
    // 验证：JPS 明确报告 class 读取损坏时可触发一次全量重建。
    assertTrue(SimpleIde.isRecoverableIncrementalBuildFailure(List.of("error reading /tmp/out/ApplicationService$10.class; bad class file")));
    // 验证：单独缺少 Builder 方法仍按真实编译错误处理。
    assertFalse(SimpleIde.isRecoverableIncrementalBuildFailure(List.of("cannot find symbol: method builder()")));
  }
}
