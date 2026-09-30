package io.simpleide;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
    // 验证：构建帮助说明本地排队锁。
    assertTrue(SimpleIde.helpText("build").contains("本地构建排队锁"));
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

  /** 验证 build_project 响应中过滤 WARNING 级别的告警信息以减少 Token。 */
  @Test
  void filters_out_warnings_from_build_problems() {
    String mcpResultWithWarnings = "{\"content\":[{\"text\":\"{\\\"isSuccess\\\":true,\\\"problems\\\":[{\\\"kind\\\":\\\"WARNING\\\",\\\"file\\\":\\\"src/Main.java\\\",\\\"line\\\":10,\\\"column\\\":5,\\\"message\\\":\\\"已过时\\\"}],\\\"timedOut\\\":false}\",\"type\":\"text\"}],\"isError\":false}";
    String result = SimpleIde.mcpResultToBuildJson(mcpResultWithWarnings);
    // 验证：构建成功时 success 为 true。
    assertTrue(result.contains("\"success\":true"));
    // 验证：过滤掉 WARNING 信息，problems 数组为空。
    assertTrue(result.contains("\"problems\":[]"));
    // 验证：输出结果不包含已过时的告警信息。
    assertFalse(result.contains("已过时"));
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

  /** 验证测试前置编译失败时的测试响应结构。 */
  @Test
  void formats_test_pre_build_failure_response() {
    String errorBuildJson = "{\"success\":false,\"problems\":[{\"kind\":\"ERROR\",\"file\":\"src/test/java/com/example/AppTest.java\",\"line\":10,\"column\":5,\"message\":\"程序包不存在\"}],\"engine\":\"idea-mcp\"}";
    String response = SimpleIde.formatTestPreBuildFailure("com.example.AppTest", "testSomething", errorBuildJson);
    // 验证：响应中 success 为 false。
    assertTrue(response.contains("\"success\":false"));
    // 验证：响应中包含目标测试类名。
    assertTrue(response.contains("\"testClass\":\"com.example.AppTest\""));
    // 验证：响应中包含测试方法名。
    assertTrue(response.contains("\"method\":\"testSomething\""));
    // 验证：响应包含前置编译失败明确原因。
    assertTrue(response.contains("测试前置编译失败"));
    // 验证：响应包含编译问题详情。
    assertTrue(response.contains("程序包不存在"));
  }

  /** 验证成功运行的测试结果被正确解析为结构化数据，且过滤无用噪音日志。 */
  @Test
  void parses_successful_teamcity_test_result() {
    String rawOutput = "##teamcity[enteredTheMatrix]\n"
      + "##teamcity[testStarted name='DemoTest.testPass1']\n"
      + "17:10:50,685 |-INFO in ch.qos.logback.classic.joran.action.LoggerAction - Setting level of logger to OFF\n"
      + "##teamcity[testFinished name='DemoTest.testPass1' duration='100']\n"
      + "##teamcity[testStarted name='DemoTest.testPass2']\n"
      + "##teamcity[testFinished name='DemoTest.testPass2' duration='50']\n"
      + "##teamcity[testSuiteFinished name='DemoTest' duration='150']\n";

    String mcpJson = "{\"exitCode\":0,\"output\":\"" + rawOutput.replace("\n", "\\n").replace("\"", "\\\"") + "\"}";
    String result = SimpleIde.mcpTestResultToJson(mcpJson, "com.example.DemoTest", null);

    // 验证：测试执行状态为成功。
    assertTrue(result.contains("\"success\":true"));
    // 验证：解析出总测试数为 2。
    assertTrue(result.contains("\"total\":2"));
    // 验证：解析出通过测试数为 2。
    assertTrue(result.contains("\"passed\":2"));
    // 验证：解析出失败测试数为 0。
    assertTrue(result.contains("\"failed\":0"));
    // 验证：过滤掉了 Logback 内部诊断噪音。
    assertFalse(result.contains("ch.qos.logback"));
    // 验证：精简输出包含成功摘要。
    assertTrue(result.contains("All 2 tests passed"));
  }

  /** 验证测试失败时直接在顶层暴露 failures 关键问题与精准代码位置。 */
  @Test
  void parses_failed_teamcity_test_result_and_exposes_key_failures() {
    String stack = "org.junit.ComparisonFailure: expected:<[a]> but was:<[b]>\n"
      + "\tat com.example.DemoTest.testFail(DemoTest.java:42)\n"
      + "\tat sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)\n";
    String tcDetails = stack.replace("\n", "|n");

    String rawOutput = "##teamcity[enteredTheMatrix]\n"
      + "##teamcity[testStarted name='DemoTest.testPass']\n"
      + "##teamcity[testFinished name='DemoTest.testPass' duration='30']\n"
      + "##teamcity[testStarted name='DemoTest.testFail']\n"
      + "##teamcity[testFailed name='DemoTest.testFail' message='expected:<[a]> but was:<[b]>' details='" + tcDetails + "']\n"
      + "##teamcity[testFinished name='DemoTest.testFail' duration='20']\n";

    String mcpJson = "{\"exitCode\":1,\"output\":\"" + rawOutput.replace("\n", "\\n").replace("\"", "\\\"").replace("'", "\\'") + "\"}";
    String result = SimpleIde.mcpTestResultToJson(mcpJson, "com.example.DemoTest", null);

    // 验证：测试执行状态为失败。
    assertTrue(result.contains("\"success\":false"));
    // 验证：解析出总测试数为 2。
    assertTrue(result.contains("\"total\":2"));
    // 验证：解析出失败测试数为 1。
    assertTrue(result.contains("\"failed\":1"));
    // 验证：顶层暴露 failures 数组。
    assertTrue(result.contains("\"failures\":["));
    // 验证：失败信息包含断言对比差异。
    assertTrue(result.contains("expected:<[a]> but was:<[b]>"));
    // 验证：精准定位到测试失败源码行。
    assertTrue(result.contains("DemoTest.java:42"));
  }

  /** 验证当存在 fullOutputPath 临时文件时，优先读取完整文件以绕过 10000 字符截断。 */
  @Test
  void reads_full_output_file_when_present() throws Exception {
    Path tempFile = Files.createTempFile("ij_run_test", ".log");
    String fullContent = "##teamcity[testStarted name='DemoTest.longLogTest']\n"
      + "##teamcity[testFinished name='DemoTest.longLogTest' duration='88']\n";
    Files.write(tempFile, fullContent.getBytes(StandardCharsets.UTF_8));

    // 模拟 IDEA MCP 返回已被截断的 output，但提供了 fullOutputPath
    String truncatedOutput = "17:10:50,685 |-INFO in ch.qos.logback<truncated>";
    String mcpJson = "{\"exitCode\":0,\"output\":\"" + truncatedOutput + "\",\"fullOutputPath\":\"" + tempFile.toString() + "\"}";
    String result = SimpleIde.mcpTestResultToJson(mcpJson, "com.example.DemoTest", null);

    Files.deleteIfExists(tempFile);

    // 验证：成功从完整临时文件中解析出测试用例，未受 truncated 影响。
    assertTrue(result.contains("\"total\":1"));
    // 验证：测试通过数为 1。
    assertTrue(result.contains("\"passed\":1"));
  }

  /** 验证从 MCP 聚合诊断结果中提取改动文件的无用声明信息。 */
  @Test
  void extracts_unused_diagnostics_for_modified_files() {
    String mcpDiagnosticsJson = "{\"content\":[{\"text\":\"{\\\"ok\\\":true,\\\"data\\\":{\\\"summary\\\":{\\\"total_errors\\\":0,\\\"total_warnings\\\":5,\\\"modules_affected\\\":[\\\"artemis\\\"]},\\\"by_module\\\":{\\\"artemis\\\":{\\\"errors\\\":0,\\\"warnings\\\":5,\\\"top_problems\\\":["
      + "{\\\"file\\\":\\\"/project/src/TargetService.java\\\",\\\"line\\\":42,\\\"message\\\":\\\"private 字段 'oldCache' 从未使用\\\",\\\"category\\\":\\\"INSPECTION\\\"},"
      + "{\\\"file\\\":\\\"/project/src/TargetService.java\\\",\\\"line\\\":55,\\\"message\\\":\\\"变量 'unusedLocal' 从未分配\\\",\\\"category\\\":\\\"INSPECTION\\\"},"
      + "{\\\"file\\\":\\\"/project/src/TargetService.java\\\",\\\"line\\\":60,\\\"message\\\":\\\"形参 'redundantParam' 从未使用\\\",\\\"category\\\":\\\"INSPECTION\\\"},"
      + "{\\\"file\\\":\\\"/project/src/TargetService.java\\\",\\\"line\\\":70,\\\"message\\\":\\\"变量 'temp' 初始值设定项 'null' 冗余\\\",\\\"category\\\":\\\"INSPECTION\\\"},"
      + "{\\\"file\\\":\\\"/project/src/TargetService.java\\\",\\\"line\\\":80,\\\"message\\\":\\\"方法 'deprecatedMethod' 已过时\\\",\\\"category\\\":\\\"INSPECTION\\\"},"
      + "{\\\"file\\\":\\\"/project/src/UnrelatedService.java\\\",\\\"line\\\":10,\\\"message\\\":\\\"private 字段 'otherUnused' 从未使用\\\",\\\"category\\\":\\\"INSPECTION\\\"}"
      + "]}}}}\",\"type\":\"text\"}],\"isError\":false}";

    List<String> modifiedFiles = Arrays.asList("src/TargetService.java");
    List<Map<String, Object>> unused = SimpleIde.extractUnusedDiagnosticsFromMcp(mcpDiagnosticsJson, modifiedFiles, "/project");

    // 验证：提取出的无用声明数量为 4（包含无用字段、未分配、无用形参、冗余初始值，过滤掉了已过时和无关文件）。
    assertEquals(4, unused.size());
    // 验证：包含行号为 42 的无用字段。
    assertEquals(42, unused.get(0).get("line"));
    // 验证：包含从未使用信息。
    assertTrue(((String) unused.get(0).get("message")).contains("从未使用"));
    // 验证：包含行号为 55 的未分配变量。
    assertEquals(55, unused.get(1).get("line"));
    // 验证：包含行号为 60 的无用形参。
    assertEquals(60, unused.get(2).get("line"));
    // 验证：包含行号为 70 的冗余初始值设定。
    assertEquals(70, unused.get(3).get("line"));
    assertTrue(((String) unused.get(3).get("message")).contains("冗余"));
  }

  /** 验证将 unused 列表追加到构建 JSON 中。 */
  @Test
  void appends_unused_list_to_build_json() {
    String baseBuildJson = "{\"success\":true,\"problems\":[],\"engine\":\"idea-mcp\"}";
    List<Map<String, Object>> unused = new ArrayList<Map<String, Object>>();
    unused.add(SimpleIde.mapOf("file", "src/TargetService.java", "line", 42, "message", "private 字段 'oldCache' 从未使用"));

    String updatedBuildJson = SimpleIde.appendUnusedToBuildJson(baseBuildJson, unused);

    // 验证：构建成功标识保留。
    assertTrue(updatedBuildJson.contains("\"success\":true"));
    // 验证：输出包含 unused 数组。
    assertTrue(updatedBuildJson.contains("\"unused\":["));
    // 验证：包含目标变量名与行号。
    assertTrue(updatedBuildJson.contains("oldCache"));
    assertTrue(updatedBuildJson.contains("42"));
  }

}



