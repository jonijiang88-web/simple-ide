package io.simpleide;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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
    Path file = Files.createTempFile("simple-ide", ".log");
    Files.write(file, "first\nsecond\nthird\n".getBytes(StandardCharsets.UTF_8));
    // 验证：日志尾部保留最新两行。
    assertEquals(Arrays.asList("second", "third"), SimpleIde.tail(file, 2));
  }

  /** 验证帮助信息包含 MCP 端口说明。 */
  @Test
  void describes_runtime_commands() {
    // 验证：运行帮助说明随机端口参数。
    assertTrue(SimpleIde.helpText("run").contains("--random-port"));
    // 验证：构建帮助说明 IDEA MCP 编译。
    assertTrue(SimpleIde.helpText("build").contains("IDEA MCP"));
    // 验证：顶层帮助列出日志查询命令。
    assertTrue(SimpleIde.helpText(null).contains("logs"));
    // 验证：顶层帮助列出 MCP 端口环境变量。
    assertTrue(SimpleIde.helpText(null).contains("IJ_MCP_SERVER_PORT"));
  }

  /** 验证默认 MCP 端口为 64342。 */
  @Test
  void default_mcp_port() {
    // 验证：默认 MCP 端口为 64342。
    assertEquals(64342, SimpleIde.DEFAULT_MCP_PORT);
  }

  /** 验证 JSON 序列化基本类型。 */
  @Test
  void json_serialization() {
    // 验证：字符串值被正确转义。
    assertEquals("\"hello\"", SimpleIde.json("hello"));
    // 验证：布尔值序列化正确。
    assertEquals("true", SimpleIde.json(true));
    // 验证：数字序列化正确。
    assertEquals("42", SimpleIde.json(42));
  }

  /** 验证 build_project 成功响应的解析。 */
  @Test
  void parses_build_project_success_response() {
    String mcpResult = "{\"content\":[{\"text\":\"{\\\"isSuccess\\\":true,\\\"problems\\\":[],\\\"timedOut\\\":false}\",\"type\":\"text\"}],\"isError\":false}";
    String result = SimpleIde.mcpResultToBuildJson(mcpResult);
    // 验证：构建成功时 success 为 true。
    assertTrue(result.contains("\"success\":true"));
    // 验证：构建引擎标识为 idea-mcp。
    assertTrue(result.contains("\"engine\":\"idea-mcp\""));
  }

  /** 验证 build_project 错误与超时响应的解析。 */
  @Test
  void parses_build_project_error_and_timeout_response() {
    String errorMcpResult = "{\"content\":[{\"text\":\"{\\\"isSuccess\\\":false,\\\"problems\\\":[{\\\"kind\\\":\\\"ERROR\\\",\\\"file\\\":\\\"/path/Demo.java\\\",\\\"line\\\":10,\\\"column\\\":5,\\\"message\\\":\\\"找不到符号\\\"}],\\\"timedOut\\\":false}\",\"type\":\"text\"}],\"isError\":false}";
    String errorResult = SimpleIde.mcpResultToBuildJson(errorMcpResult);
    // 验证：构建包含错误时 success 为 false。
    assertTrue(errorResult.contains("\"success\":false"));
    // 验证：错误信息中包含具体的编译报错消息。
    assertTrue(errorResult.contains("找不到符号"));

    String timeoutMcpResult = "{\"content\":[{\"text\":\"{\\\"isSuccess\\\":false,\\\"problems\\\":[],\\\"timedOut\\\":true}\",\"type\":\"text\"}],\"isError\":false}";
    String timeoutResult = SimpleIde.mcpResultToBuildJson(timeoutMcpResult);
    // 验证：构建超时时 success 为 false。
    assertTrue(timeoutResult.contains("\"success\":false"));
    // 验证：超时结果包含超时提示信息。
    assertTrue(timeoutResult.contains("超时"));
  }

  /** 验证从 git status 输出解析修改的源文件相对路径。 */
  @Test
  void parses_git_status_modified_source_files() {
    String gitOutput = " M src/main/java/com/example/App.java\n"
      + "M  src/test/java/com/example/AppTest.java\n"
      + "?? src/main/java/com/example/NewFile.kt\n"
      + "?? README.md\n"
      + " D src/main/java/com/example/Deleted.java\n";
    List<String> files = SimpleIde.parseGitStatusOutput(gitOutput);
    // 验证：解析出 3 个修改/新增的 Java/Kotlin 源文件。
    assertEquals(3, files.size());
    // 验证：包含 App.java。
    assertTrue(files.contains("src/main/java/com/example/App.java"));
    // 验证：包含 AppTest.java。
    assertTrue(files.contains("src/test/java/com/example/AppTest.java"));
    // 验证：包含 NewFile.kt。
    assertTrue(files.contains("src/main/java/com/example/NewFile.kt"));
    // 验证：不包含非源文件 README.md。
    assertFalse(files.contains("README.md"));
    // 验证：不包含已删除的源文件。
    assertFalse(files.contains("src/main/java/com/example/Deleted.java"));
  }
}


