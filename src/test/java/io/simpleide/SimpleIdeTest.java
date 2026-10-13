package io.simpleide;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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

  /** 验证依赖 Classpath 缓存优先取自独立的 .jps-deps，并回退历史目录 .jps-cache。 */
  @Test
  void resolves_classpath_cache_from_deps_before_legacy_cache() throws Exception {
    Path project = Files.createTempDirectory("simple-ide-classpath");

    // 验证：无任何缓存目录时返回 null，供启动流程据此给出显式告警。
    assertNull(SimpleIde.findClasspathCacheFile(project));

    Path legacyDir = Files.createDirectories(project.resolve(".jps-cache"));
    Files.write(legacyDir.resolve("timestamp.cache"), "x\n".getBytes(StandardCharsets.UTF_8));
    // 验证：非 classpath- 前缀的缓存文件不被误识别为依赖缓存。
    assertNull(SimpleIde.findClasspathCacheFile(project));

    Path legacyCache = Files.write(legacyDir.resolve("classpath-demo.cache"), "legacy\n".getBytes(StandardCharsets.UTF_8));
    // 验证：仅存在历史目录缓存时回退命中该文件，保证旧项目仍可启动。
    assertEquals(legacyCache.toFile(), SimpleIde.findClasspathCacheFile(project));

    Path depsDir = Files.createDirectories(project.resolve(".jps-deps"));
    Path depsCache = Files.write(depsDir.resolve("classpath-demo.cache"), "deps\n".getBytes(StandardCharsets.UTF_8));
    // 验证：独立依赖缓存优先于历史位置，避免 JPS 清理 .jps-cache 时连带删除依赖缓存。
    assertEquals(depsCache.toFile(), SimpleIde.findClasspathCacheFile(project));
  }

  /** 验证 auto-rebuild 与显式 rebuild 的语义差异，以及不会因真实源码错误触发重建。 */
  @Test
  void auto_rebuild_only_for_cache_corruption() {
    // 验证：命中缓存损坏且非源码错误时触发一次自动重建
    assertTrue(SimpleIde.shouldAutoRebuild(true, false, true, false));
    // 验证：真实源码错误不触发重建（避免掩盖代码问题）
    assertFalse(SimpleIde.shouldAutoRebuild(true, false, true, true));
    // 验证：无缓存损坏证据不触发重建
    assertFalse(SimpleIde.shouldAutoRebuild(true, false, false, false));
    // 验证：未启用 auto-rebuild 时不重建
    assertFalse(SimpleIde.shouldAutoRebuild(false, false, true, false));
    // 验证：显式 rebuild 走独立路径，不再叠加 auto 重建
    assertFalse(SimpleIde.shouldAutoRebuild(true, true, true, false));
  }

  /** 验证持久化的小工具版本与缓存格式版本可追溯。 */
  @Test
  void exposes_version_metadata() {
    // 验证：工具版本非空
    assertNotNull(SimpleIde.TOOL_VERSION);
    // 验证：缓存格式版本与 jps-cli 约定一致为 2
    assertEquals("2", SimpleIde.CACHE_FORMAT_VERSION);
  }

  /** 验证帮助信息包含新增的构建状态与自动重建说明。 */
  @Test
  void documents_build_scope_and_status() {
    // 验证：构建帮助说明 --auto-rebuild 的受控语义
    assertTrue(SimpleIde.helpText("build").contains("--auto-rebuild"));
    // 验证：构建帮助说明 --rebuild 的运行中应用保护
    assertTrue(SimpleIde.helpText("build").contains("运行中的 Application"));
    // 验证：顶层帮助列出 build-status 命令
    assertTrue(SimpleIde.helpText(null).contains("build-status"));
    // 验证：顶层帮助列出 version 命令
    assertTrue(SimpleIde.helpText(null).contains("version"));
    // 验证：build-status 帮助说明不提供百分比或 ETA
    assertTrue(SimpleIde.helpText("build-status").contains("不提供百分比"));
  }

  /** 验证测试源码可跨模块定位（多模块项目）。 */
  @Test
  void resolves_test_source_in_modules() throws Exception {
    Path project = Files.createTempDirectory("simple-ide-testlocate");
    Files.createDirectories(project.resolve("app/src/test/java/com/example"));
    Files.write(project.resolve("pom.xml"),
      ("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n  <artifactId>root</artifactId>\n"
        + "  <packaging>pom</packaging><modules><module>app</module></modules>\n</project>\n").getBytes(StandardCharsets.UTF_8));
    Files.write(project.resolve("app/pom.xml"),
      ("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n  <artifactId>app</artifactId>\n</project>\n").getBytes(StandardCharsets.UTF_8));
    Path testFile = project.resolve("app/src/test/java/com/example/AppTest.java");
    Files.write(testFile, "package com.example;\npublic class AppTest {}\n".getBytes(StandardCharsets.UTF_8));

    // 验证：能在子模块的 src/test/java 下定位测试源文件
    assertEquals(testFile, SimpleIde.resolveTestSourceFile(project, "com.example.AppTest", null));
    // 验证：定位不到的测试类返回 null
    assertNull(SimpleIde.resolveTestSourceFile(project, "com.example.MissingTest", null));
  }

  /** 验证 lastJsonObjectLine 的兼容实现保持可用。 */
  @Test
  void selects_last_json_line() {
    // 验证：取最后一个可解析 JSON 行
    assertEquals("{\"success\":false}", SimpleIde.lastJsonObjectLine("log\n{\"success\":false}\n"));
    // 验证：无 JSON 时返回 null
    assertNull(SimpleIde.lastJsonObjectLine("no json"));
  }

  /** 【R3-3】验证隔离缓存判定：运行中应用+未授权清理默认隔离，可强制/关闭。 */
  @Test
  void isolates_cache_only_when_running_and_not_authorized() {
    // 验证：存在运行中应用且未授权清理时默认隔离
    assertTrue(SimpleIde.shouldIsolateCache(false, false, false, 1));
    // 验证：没有运行中应用时不隔离（避免不必要的缓存复制开销）
    assertFalse(SimpleIde.shouldIsolateCache(false, false, false, 0));
    // 验证：显式 --isolate-cache 可强制隔离
    assertTrue(SimpleIde.shouldIsolateCache(false, true, false, 0));
    // 验证：--no-isolate-cache 可关闭保护
    assertFalse(SimpleIde.shouldIsolateCache(false, false, true, 2));
    // 验证：清理重建时不需要隔离（本来就要删缓存）
    assertFalse(SimpleIde.shouldIsolateCache(true, true, false, 2));
  }

  /** 验证帮助信息说明隔离缓存与依赖失败退出码。 */
  @Test
  void documents_cache_isolation_and_exit_codes() {
    // 验证：build 帮助说明 --isolate-cache
    assertTrue(SimpleIde.helpText("build").contains("--isolate-cache"));
    // 验证：build 帮助说明 --no-isolate-cache
    assertTrue(SimpleIde.helpText("build").contains("--no-isolate-cache"));
    // 验证：build 帮助说明依赖解析失败退出码
    assertTrue(SimpleIde.helpText("build").contains("依赖解析失败=4"));
  }

  /** 验证帮助信息包含运行时命令说明。 */
  @Test
  void describes_runtime_commands() {
    // 验证：运行帮助说明随机端口参数。
    assertTrue(SimpleIde.helpText("run").contains("--random-port"));
    // 验证：运行帮助说明调试端口参数。
    assertTrue(SimpleIde.helpText("run").contains("--debug-port"));
    // 验证：构建帮助说明 JPS 增量引擎。
    assertTrue(SimpleIde.helpText("build").contains("JPS"));
    // 验证：构建帮助说明本地排队锁。
    assertTrue(SimpleIde.helpText("build").contains("本地构建排队锁"));
    // 验证：测试帮助说明内置 JPS 增量引擎。
    assertTrue(SimpleIde.helpText("test").contains("JPS"));
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

  /** 验证能从 mcp_ext_status 响应中解析出宿主项目 base 路径。 */
  @Test
  void extracts_project_base_path_from_mcp_ext_status_response() {
    String response = "{\"content\":[{\"text\":\"{\\\"ok\\\":true,\\\"data\\\":{\\\"pluginVersion\\\":\\\"0.1.0-SNAPSHOT\\\",\\\"projectName\\\":\\\"dev\\\",\\\"projectBasePath\\\":\\\"/home/lijiang/code/artemis/dev\\\",\\\"mcpServerEnabled\\\":true},\\\"error\\\":null,\\\"warnings\\\":[]}\",\"type\":\"text\"}],\"isError\":false}";
    // 验证：解析出的 base 路径与响应中的 projectBasePath 一致。
    assertEquals("/home/lijiang/code/artemis/dev", SimpleIde.extractProjectBasePath(response));
  }

  /** 验证宿主项目退化为父目录（嵌套项目）时校验必须失败。 */
  @Test
  void rejects_degraded_nested_project_target() {
    try {
      SimpleIde.checkMcpTarget("/home/lijiang/code/ai-e2e-test/code/repository/artemis", "/home/lijiang/code/ai-e2e-test");
      fail("目标项目退化为父目录时应当抛出异常");
    }
    catch (IOException expected) {
      // 验证：错误信息同时给出请求路径与实际宿主路径，便于定位。
      assertTrue(expected.getMessage().contains("/home/lijiang/code/ai-e2e-test/code/repository/artemis"));
      assertTrue(expected.getMessage().contains("/home/lijiang/code/ai-e2e-test"));
    }
  }

  /** 验证宿主项目一致时校验放行。 */
  @Test
  void accepts_matching_mcp_target() throws Exception {
    // 验证：请求路径与宿主路径完全一致时不抛异常。
    SimpleIde.checkMcpTarget("/home/lijiang/code/artemis/dev", "/home/lijiang/code/artemis/dev");
  }

  /** 验证无法确认宿主项目时必须失败，不能静默放行。 */
  @Test
  void rejects_unresolved_mcp_target() {
    try {
      SimpleIde.checkMcpTarget("/home/lijiang/code/artemis/dev", null);
      fail("无法确认宿主项目时应当抛出异常");
    }
    catch (IOException expected) {
      // 验证：错误信息说明无法确认目标项目。
      assertTrue(expected.getMessage().contains("无法确认"));
    }
  }

  /** 验证 MCP 工具级错误（isError=true）能被识别。 */
  @Test
  void detects_is_error_tool_result() {
    String result = "{\"content\":[{\"text\":\"File not found: /home/lijiang/code/ai-e2e-test/src/main/java/Nope.java\",\"type\":\"text\"}],\"isError\":true}";
    // 验证：返回的错误文本包含原始错误信息。
    assertTrue(McpClient.toolErrorMessage(result).contains("File not found"));
  }

  /** 验证成功结果不会误判为错误。 */
  @Test
  void ignores_successful_tool_result() {
    String result = "{\"content\":[{\"text\":\"{\\\"isSuccess\\\":true,\\\"problems\\\":[]}\",\"type\":\"text\"}],\"isError\":false}";
    // 验证：成功结果不产生错误消息。
    assertNull(McpClient.toolErrorMessage(result));
  }

  /** 验证显式 --file 优先于 git 改动探测。 */
  @Test
  void prefers_explicit_files_over_git_changes() {
    // 验证：显式指定文件时原样返回，不触发 git 探测。
    assertEquals(Arrays.asList("src/A.java"),
      SimpleIde.resolveFilesToRebuild(java.nio.file.Paths.get("/nonexistent-project"), Arrays.asList("src/A.java")));
  }

  /** 验证非 git 目录下不会产生待编译文件列表。 */
  @Test
  void falls_back_to_empty_files_when_not_a_git_repository() {
    // 验证：无 git 仓库且未显式指定文件时返回空列表。
    assertTrue(SimpleIde.resolveFilesToRebuild(java.nio.file.Paths.get("/nonexistent-project"), new ArrayList<String>()).isEmpty());
  }

  /** 验证 --file 传入的路径被归一化为相对项目根路径。 */
  @Test
  void normalizes_file_path_to_project_relative() {
    // 验证：项目内的绝对路径转为相对项目根路径（filesToRebuild 要求相对路径）。
    assertEquals("src/main/A.java",
      SimpleIde.toProjectRelative(java.nio.file.Paths.get("/proj"), "/proj/src/main/A.java"));
    // 验证：相对路径按项目根解析后仍为相对路径。
    assertEquals("src/main/A.java",
      SimpleIde.toProjectRelative(java.nio.file.Paths.get("/proj"), "src/main/A.java"));
  }

  /** 验证 git rename 输出只保留新路径。 */
  @Test
  void parses_renamed_file_to_new_path() {
    // 验证：R 状态取箭头后的新路径，避免把 "old -> new" 整串当作文件路径。
    assertEquals(Arrays.asList("src/B.java"), SimpleIde.parseGitStatusOutput("R  src/A.java -> src/B.java\n"));
  }

  /** 验证 npm 编译结果识别：成功、失败与未知三种情况。 */
  @Test
  void detects_npm_compile_status() {
    // 验证：rspack 成功汇总行判定为 ok。
    assertEquals("ok", SimpleIde.detectCompileStatus("Rspack compiled successfully in 1.10 s"));
    // 验证：rspack 报错汇总行判定为 failed。
    assertEquals("failed", SimpleIde.detectCompileStatus("Rspack compiled with 3 errors in 7.09 s"));
    // 验证：webpack 报错汇总行同样判定为 failed。
    assertEquals("failed", SimpleIde.detectCompileStatus("webpack compiled with 2 errors"));
    // 验证：仅有 Module not found 明细时也判定为 failed。
    assertEquals("failed", SimpleIde.detectCompileStatus("ERROR in ./client.js\n  Module not found: Can't resolve 'x'"));
    // 验证：修好后的成功汇总行覆盖先前的报错，最终为 ok。
    assertEquals("ok", SimpleIde.detectCompileStatus("ERROR in ./a.js\nRspack compiled with 1 error\nRspack compiled successfully in 300 ms"));
    // 验证：日志中尚无编译结果时返回 null，便于上层标记 unknown。
    assertNull(SimpleIde.detectCompileStatus("Project is running at: http://127.0.0.1:32937/"));
  }

  /** 验证编译失败证据优先取 ERROR 明细行。 */
  @Test
  void exposes_compile_failure_evidence() {
    // 验证：同时存在明细与汇总时优先返回 ERROR 明细。
    assertEquals("ERROR in ./client.js 22:0-70",
      SimpleIde.lastCompileEvidence("ERROR in ./client.js 22:0-70\nRspack compiled with 3 errors in 7.09 s"));
    // 验证：无明细时回退到编译汇总行。
    assertEquals("Rspack compiled with 3 errors in 7.09 s",
      SimpleIde.lastCompileEvidence("Rspack compiled with 3 errors in 7.09 s"));
  }

  /** 验证 package.json 依赖解析只取直接依赖键。 */
  @Test
  void parses_declared_dependencies() {
    String packageJson = "{"
      + "\"name\":\"demo\","
      + "\"dependencies\":{\"react\":\"^17.0.0\",\"@scope/pkg\":\"^1.0.0\"},"
      + "\"devDependencies\":{\"vitest\":\"^1.0.0\"},"
      + "\"scripts\":{\"dev\":\"rspack serve\"}"
      + "}";
    List<String> names = SimpleIde.declaredDependencies(packageJson);
    // 验证：dependencies 与 devDependencies 的直接依赖均被解析。
    assertTrue(names.containsAll(Arrays.asList("react", "@scope/pkg", "vitest")));
    // 验证：scripts 等非依赖字段不被计入，且不误取版本号等字符串值。
    assertEquals(3, names.size());
  }

  /** 验证依赖缺失检测能定位未安装的直接依赖。 */
  @Test
  void reports_missing_npm_dependencies() throws Exception {
    Path project = Files.createTempDirectory("simple-ide");
    Files.createDirectories(project.resolve("node_modules/react"));
    Files.createDirectories(project.resolve("node_modules/@scope/pkg"));
    String packageJson = "{\"dependencies\":{\"react\":\"^17.0.0\",\"@scope/pkg\":\"^1.0.0\",\"react-helios-kanban-companion\":\"^2.0.1\"}}";
    // 验证：仅列出 node_modules 中缺失的直接依赖。
    assertEquals(Arrays.asList("react-helios-kanban-companion"), SimpleIde.missingNpmDependencies(project, packageJson));
    // 验证：声明齐全且均已安装时返回空列表。
    assertTrue(SimpleIde.missingNpmDependencies(project, "{\"dependencies\":{\"react\":\"^17.0.0\"}}").isEmpty());
  }

  /** 验证 npm 依赖校验在缺失时抛出可操作错误。 */
  @Test
  void fails_fast_when_dependencies_missing() throws Exception {
    Path project = Files.createTempDirectory("simple-ide");
    Files.write(project.resolve("package.json"),
      "{\"dependencies\":{\"react-helios-kanban-companion\":\"^2.0.1\"}}".getBytes(StandardCharsets.UTF_8));
    try {
      SimpleIde.ensureNpmDependencies(project);
      fail("依赖缺失时应抛出异常");
    }
    catch (IllegalStateException expected) {
      // 验证：错误信息包含缺失包名与 npm install 修复指引。
      assertTrue(expected.getMessage().contains("react-helios-kanban-companion"));
      assertTrue(expected.getMessage().contains("npm install"));
    }
  }

  /** 验证读取日志末尾字节用于编译判定。 */
  @Test
  void reads_log_tail_bytes() throws Exception {
    Path log = Files.createTempFile("simple-ide", ".log");
    Files.write(log, "old\nRspack compiled successfully in 1 s\n".getBytes(StandardCharsets.UTF_8));
    // 验证：末尾截取保留最新编译行，供状态判定使用。
    assertTrue(SimpleIde.readLogTail(log, 64).contains("compiled successfully"));
  }

  /** 验证 npm 帮助说明新增的依赖校验与状态语义。 */
  @Test
  void describes_npm_safeguards() {
    // 验证：npm run 帮助说明依赖校验开关。
    assertTrue(SimpleIde.helpText("npm run").contains("--skip-deps-check"));
    // 验证：npm status 帮助说明编译失败返回 FAILED。
    assertTrue(SimpleIde.helpText("npm status").contains("FAILED"));
  }

  /** 验证能够正确定位内置的 jps-cli.jar 依赖。 */
  @Test
  void finds_jps_cli_jar() {
    // 验证：成功检索到 jps-cli.jar 路径
    org.junit.jupiter.api.Assertions.assertNotNull(SimpleIde.findJpsCliJar());
    // 验证：路径对应实际存在的常规文件
    assertTrue(Files.isRegularFile(SimpleIde.findJpsCliJar()));
  }

  /** 验证 LocalTestRunner 测试入口类保持稳定。 */
  @Test
  void loads_local_test_runner_entrypoint() {
    // 验证：LocalTestRunner 类可正常加载且全限定名符合预期
    assertEquals("io.simpleide.LocalTestRunner", LocalTestRunner.class.getName());
  }

  /** 验证 status 默认使用 local engine 且在无进程时返回 STOPPED，不调用 MCP。 */
  @Test
  void status_defaults_to_local_engine_and_returns_stopped() throws Exception {
    Path tempProject = Files.createTempDirectory("simple-ide-status-test");
    SimpleIde.Command cmd = SimpleIde.Command.parse(new String[]{"status", "--project", tempProject.toString()});
    Map<String, Object> result = SimpleIde.queryStatus(cmd);

    // 验证：默认 engine 为 local
    assertEquals("local", result.get("engine"));
    // 验证：状态返回 STOPPED
    assertEquals("STOPPED", result.get("status"));
    // 验证：running 标记为 false
    assertEquals(false, result.get("running"));
  }

  /** 验证 status 在进程已死时自动清理过期状态文件。 */
  @Test
  void status_cleans_expired_state_file_when_process_dead() throws Exception {
    Path tempProject = Files.createTempDirectory("simple-ide-status-clean-test");
    Path stateFile = tempProject.resolve(".simple-ide/runs/Application.properties");
    Files.createDirectories(stateFile.getParent());
    Files.write(stateFile, Arrays.asList("9999999", "/tmp/dummy.log", "9999", tempProject.toString(), "java", "-jar", "app.jar"), StandardCharsets.UTF_8);

    SimpleIde.Command cmd = SimpleIde.Command.parse(new String[]{"status", "--project", tempProject.toString()});
    Map<String, Object> result = SimpleIde.queryStatus(cmd);

    // 验证：状态返回 STOPPED
    assertEquals("STOPPED", result.get("status"));
    // 验证：过期状态文件已被清理
    assertFalse(Files.exists(stateFile));
  }

  /** 验证 status --engine idea-mcp 在 MCP 不可用时返回明确错误且不启动 IDEA。 */
  @Test
  void status_with_idea_mcp_returns_error_when_unavailable() throws Exception {
    Path tempProject = Files.createTempDirectory("simple-ide-status-mcp-test");
    SimpleIde.Command cmd = SimpleIde.Command.parse(new String[]{"status", "--project", tempProject.toString(), "--engine", "idea-mcp"});
    Map<String, Object> result = SimpleIde.queryStatus(cmd);

    // 验证：执行失败返回 false
    assertEquals(false, result.get("success"));
    // 验证：engine 标识为 idea-mcp
    assertEquals("idea-mcp", result.get("engine"));
    // 验证：错误信息明确指出 IDEA MCP 未运行
    assertTrue(((String) result.get("error")).contains("IDEA MCP 未运行"));
  }

  /** 验证 stop 默认使用 local engine 且在无进程时直接返回已停止，不调用 MCP。 */
  @Test
  void stop_defaults_to_local_engine_and_returns_stopped() throws Exception {
    Path tempProject = Files.createTempDirectory("simple-ide-stop-test");
    SimpleIde.Command cmd = SimpleIde.Command.parse(new String[]{"stop", "--project", tempProject.toString()});
    Map<String, Object> result = SimpleIde.executeStop(cmd);

    // 验证：返回已停止标记
    assertEquals(true, result.get("stopped"));
    // 验证：默认 engine 为 local
    assertEquals("local", result.get("engine"));
  }

  /** 验证 stop 在进程已死时清理过期状态文件。 */
  @Test
  void stop_cleans_expired_state_file_when_process_dead() throws Exception {
    Path tempProject = Files.createTempDirectory("simple-ide-stop-clean-test");
    Path stateFile = tempProject.resolve(".simple-ide/runs/Application.properties");
    Files.createDirectories(stateFile.getParent());
    Files.write(stateFile, Arrays.asList("9999999", "/tmp/dummy.log", "9999", tempProject.toString(), "java", "-jar", "app.jar"), StandardCharsets.UTF_8);

    SimpleIde.Command cmd = SimpleIde.Command.parse(new String[]{"stop", "--project", tempProject.toString()});
    Map<String, Object> result = SimpleIde.executeStop(cmd);

    // 验证：返回已停止标记
    assertEquals(true, result.get("stopped"));
    // 验证：过期状态文件已被清理
    assertFalse(Files.exists(stateFile));
  }

  /** 验证 stop --engine idea-mcp 在 MCP 不可用时返回明确错误。 */
  @Test
  void stop_with_idea_mcp_returns_error_when_unavailable() throws Exception {
    Path tempProject = Files.createTempDirectory("simple-ide-stop-mcp-test");
    SimpleIde.Command cmd = SimpleIde.Command.parse(new String[]{"stop", "--project", tempProject.toString(), "--engine", "idea-mcp"});
    Map<String, Object> result = SimpleIde.executeStop(cmd);

    // 验证：执行失败返回 false
    assertEquals(false, result.get("success"));
    // 验证：engine 标识为 idea-mcp
    assertEquals("idea-mcp", result.get("engine"));
    // 验证：错误信息明确指出 IDEA MCP 未运行
    assertTrue(((String) result.get("error")).contains("IDEA MCP 未运行"));
  }

  /** 验证 connectMcp 在 MCP 端口不可用时直接返回 null，不自动启动 IDEA。 */
  @Test
  void connect_mcp_does_not_start_idea_when_unavailable() {
    Path tempProject = Paths.get("/tmp");
    // 验证：未运行 MCP 实例时返回 null，不启动后台进程
    assertNull(SimpleIde.connectMcp(tempProject));
  }

  /** 验证常规 run 命令在没有调试参数时默认禁用 JDWP 调试端口（对齐 IDEA Run 纯运行模式）。 */
  @Test
  void resolve_debug_port_defaults_to_disabled_in_run() {
    SimpleIde.Command cmd = SimpleIde.Command.parse(new String[]{"run", "--project", "/tmp", "--configuration", "App"});
    int port = SimpleIde.resolveDebugPort(cmd, new ArrayList<String>());
    // 验证：常规 run 命令默认返回 -1，不挂 JDWP 调试代理
    assertEquals(-1, port);
  }

  /** 验证 debug 子命令默认启用 JDWP 调试端口 5005（对齐 IDEA Debug 模式）。 */
  @Test
  void resolve_debug_port_defaults_to_5005_in_debug_command() {
    SimpleIde.Command cmd = SimpleIde.Command.parse(new String[]{"debug", "--project", "/tmp", "--configuration", "App"});
    int port = SimpleIde.resolveDebugPort(cmd, new ArrayList<String>());
    // 验证：debug 命令默认 JDWP 调试端口为 5005
    assertEquals(5005, port);
  }

  /** 验证 run 命令显式添加 --debug 参数时启用 JDWP 调试端口 5005。 */
  @Test
  void resolve_debug_port_defaults_to_5005_in_run_with_debug_flag() {
    SimpleIde.Command cmd = SimpleIde.Command.parse(new String[]{"run", "--project", "/tmp", "--configuration", "App", "--debug"});
    int port = SimpleIde.resolveDebugPort(cmd, new ArrayList<String>());
    // 验证：run --debug 启用 JDWP 调试端口 5005
    assertEquals(5005, port);
  }

  /** 验证指定 --debug-port 时优先使用指定端口。 */
  @Test
  void resolve_debug_port_custom_port() {
    SimpleIde.Command cmd = SimpleIde.Command.parse(new String[]{"run", "--project", "/tmp", "--configuration", "App", "--debug-port", "5008"});
    int port = SimpleIde.resolveDebugPort(cmd, new ArrayList<String>());
    // 验证：使用指定的自定义调试端口 5008
    assertEquals(5008, port);
  }

  /** 验证指定 --no-debug 时禁用调试端口。 */
  @Test
  void resolve_debug_port_disabled_by_flag() {
    SimpleIde.Command cmd = SimpleIde.Command.parse(new String[]{"debug", "--project", "/tmp", "--configuration", "App", "--no-debug"});
    int port = SimpleIde.resolveDebugPort(cmd, new ArrayList<String>());
    // 验证：指定 --no-debug 时返回 -1 表示不开启调试
    assertEquals(-1, port);
  }

  /** 验证已在 VM 参数中配置 JDWP 时能够解析已有端口。 */
  @Test
  void resolve_debug_port_parses_existing_vm_param() {
    SimpleIde.Command cmd = SimpleIde.Command.parse(new String[]{"run", "--project", "/tmp", "--configuration", "App"});
    List<String> vmParams = Arrays.asList("-Xmx2g", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5010");
    int port = SimpleIde.resolveDebugPort(cmd, vmParams);
    // 验证：从已有的 JDWP vm 参数中提取出端口 5010
    assertEquals(5010, port);
  }

  /** 验证 debug 模式下默认 5005 端口被占用时自动分配新的可用调试端口。 */
  @Test
  void resolve_debug_port_allocates_new_port_when_5005_occupied() throws Exception {
    try (ServerSocket occupied = new ServerSocket(5005)) {
      SimpleIde.Command cmd = SimpleIde.Command.parse(new String[]{"debug", "--project", "/tmp", "--configuration", "App"});
      int port = SimpleIde.resolveDebugPort(cmd, new ArrayList<String>());
      // 验证：分配的端口有效且大于 0
      assertTrue(port > 0 && port <= 65535);
      // 验证：分配的端口避开了被占用的 5005 端口
      assertTrue(port != 5005);
    }
  }

  /** 验证 queryStatus 本地模式返回包含自动分配的 debugPort。 */
  @Test
  void query_status_returns_debug_port() throws Exception {
    Path tempProject = Files.createTempDirectory("simple-ide-status-debug-port-test");
    Path stateFile = tempProject.resolve(".simple-ide/runs/Application.properties");
    Files.createDirectories(stateFile.getParent());
    SimpleIde.RunState state = new SimpleIde.RunState(9999999, Paths.get("/tmp/app.log"), 8080, 5066, tempProject.toString(), Arrays.asList("java", "Main"));
    state.write(stateFile);

    // 启动临时子进程获取真实存活 PID
    Process dummyProcess = new ProcessBuilder("sleep", "10").start();
    try {
      long alivePid = SimpleIde.getPid(dummyProcess);
      SimpleIde.RunState aliveState = new SimpleIde.RunState(alivePid, Paths.get("/tmp/app.log"), 8080, 5066, tempProject.toString(), Arrays.asList("java", "Main"));
      aliveState.write(stateFile);

      SimpleIde.Command cmd = SimpleIde.Command.parse(new String[]{"status", "--project", tempProject.toString()});
      Map<String, Object> statusResult = SimpleIde.queryStatus(cmd);

      // 验证：状态返回中包含 debugPort
      assertEquals(5066, statusResult.get("debugPort"));
    } finally {
      dummyProcess.destroy();
    }
  }

  /** 验证默认注入的 JDWP 参数使用 Java 8 也能识别的端口写法。 */
  @Test
  void build_java_command_line_injects_default_jdwp() {
    List<String> command = SimpleIde.buildJavaCommandLine(
        "java",
        Arrays.asList("-Xms512m"),
        5005,
        "/tmp/classes",
        "com.example.Main",
        8080,
        Arrays.asList("--arg1=val1")
    );
    // 验证：命令行包含 setsid
    assertEquals("setsid", command.get(0));
    // 验证：命令行包含 java 可执行程序
    assertEquals("java", command.get(1));
    // 验证：命令行包含原始 VM 参数
    assertTrue(command.contains("-Xms512m"));
    // 验证：自动生成的 JDWP 参数不使用 Java 8 无法解析的通配地址。
    assertTrue(command.contains("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005"));
    // 验证：命令行包含类路径参数
    assertTrue(command.contains("-cp"));
    // 验证：命令行包含主类
    assertTrue(command.contains("com.example.Main"));
    // 验证：命令行包含服务端口
    assertTrue(command.contains("--server.port=8080"));
    // 验证：命令行包含应用程序参数
    assertTrue(command.contains("--arg1=val1"));
  }

  /** 验证已有 JDWP 参数时 buildJavaCommandLine 不重复注入。 */
  @Test
  void build_java_command_line_skips_duplicate_jdwp() {
    String existingJdwp = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5007";
    List<String> command = SimpleIde.buildJavaCommandLine(
        "java",
        Arrays.asList("-Xms512m", existingJdwp),
        5007,
        null,
        "com.example.Main",
        -1,
        null
    );
    long jdwpCount = command.stream().filter(s -> s.contains("-agentlib:jdwp")).count();
    // 验证：即使指定了 debugPort，由于已有 JDWP 配置，不产生重复 JDWP 参数
    assertEquals(1, jdwpCount);
  }

  /** 验证 RunState 支持写入、读取并在 toMap 中包含 debugPort。 */
  @Test
  void run_state_persists_and_exposes_debug_port() throws Exception {
    Path tempFile = Files.createTempFile("run-state-test", ".properties");
    SimpleIde.RunState state = new SimpleIde.RunState(12345, Paths.get("/tmp/app.log"), 8080, 5005, "/tmp/project", Arrays.asList("java", "Main"));
    state.write(tempFile);

    SimpleIde.RunState readBack = SimpleIde.RunState.read(tempFile);
    // 验证：正确反序列化 pid
    assertEquals(12345L, readBack.pid);
    // 验证：正确反序列化 server port
    assertEquals(8080, readBack.port);
    // 验证：正确反序列化 debug port
    assertEquals(5005, readBack.debugPort);
    // 验证：正确反序列化 projectPath
    assertEquals("/tmp/project", readBack.projectPath);

    Map<String, Object> map = readBack.toMap("RUNNING");
    // 验证：toMap 中输出 debugPort 属性
    assertEquals(5005, map.get("debugPort"));
  }

  /** 验证 RunState 兼容不带 debugPort 的旧格式状态文件。 */
  @Test
  void run_state_backward_compatible_with_old_format() throws Exception {
    Path tempFile = Files.createTempFile("run-state-old", ".properties");
    // 旧格式仅 5 行：pid, log, port, projectPath, command...
    Files.write(tempFile, Arrays.asList(
        "23456",
        "/tmp/old.log",
        "8081",
        "/tmp/old-project",
        "java",
        "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5009",
        "com.example.Main"
    ), StandardCharsets.UTF_8);

    SimpleIde.RunState readBack = SimpleIde.RunState.read(tempFile);
    // 验证：正确反序列化旧文件 pid
    assertEquals(23456L, readBack.pid);
    // 验证：正确反序列化旧文件 server port
    assertEquals(8081, readBack.port);
    // 验证：从旧文件命令行中成功识别 fallback debug port 5009
    assertEquals(5009, readBack.debugPort);
    // 验证：正确反序列化旧文件 projectPath
    assertEquals("/tmp/old-project", readBack.projectPath);
  }

  /** 用于验证多项失败捕获能力的示例测试用例固件。 */
  @org.junit.jupiter.api.Tag("fixture")
  public static class MultiFailureSampleFixture {
    /** 正常通过用例。 */
    @Test
    void testPass() {
      // 验证：测试正常通过
      assertTrue(true);
    }

    /** 比较断言失败用例。 */
    @Test
    void testFailOne() {
      // 验证：断言失败抛出异常
      assertEquals("expected1", "actual1", "断言失败一");
    }

    /** 运行时异常失败用例。 */
    @Test
    void testFailTwo() {
      // 验证：显式抛出运行时异常
      throw new IllegalStateException("运行时异常失败二");
    }
  }

  /** 验证 LocalTestRunner 能够捕获所有失败用例（不仅是第一个）及其方法名与完整堆栈。 */
  @Test
  void local_test_runner_captures_all_failures_with_full_details() {
    Map<String, Object> result = LocalTestRunner.executeTests(MultiFailureSampleFixture.class.getName(), null);

    // 验证：执行结果标记为失败
    assertFalse((Boolean) result.get("success"));
    // 验证：进程退出码应为 1
    assertEquals(1, ((Number) result.get("exitCode")).intValue());
    // 验证：总用例数为 3
    assertEquals(3L, ((Number) result.get("total")).longValue());
    // 验证：通过用例数为 1
    assertEquals(1L, ((Number) result.get("passed")).longValue());
    // 验证：失败用例数为 2
    assertEquals(2L, ((Number) result.get("failed")).longValue());

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> failures = (List<Map<String, Object>>) result.get("failures");
    // 验证：失败列表中包含全部 2 个失败，而不是仅有第 1 个
    assertEquals(2, failures.size());

    // 验证：第 1 个失败包含方法名 testFailOne
    assertTrue(failures.get(0).get("test").toString().contains("testFailOne"));
    // 验证：第 1 个失败包含断言提示信息
    assertTrue(failures.get(0).get("message").toString().contains("断言失败一"));
    // 验证：第 1 个失败包含完整堆栈信息
    assertTrue(failures.get(0).get("details").toString().contains("AssertionFailedError") || failures.get(0).get("details").toString().contains("AssertionError"));

    // 验证：第 2 个失败包含方法名 testFailTwo
    assertTrue(failures.get(1).get("test").toString().contains("testFailTwo"));
    // 验证：第 2 个失败包含运行时异常信息
    assertTrue(failures.get(1).get("message").toString().contains("运行时异常失败二"));
    // 验证：第 2 个失败包含完整堆栈信息
    assertTrue(failures.get(1).get("details").toString().contains("IllegalStateException"));
  }

  /** 验证生成测试报告文件，并向返回结果中注入 reportFile 绝对路径。 */
  @Test
  void writes_test_report_file_and_injects_path() throws Exception {
    Path tempDir = Files.createTempDirectory("simple-ide-report-test");
    Map<String, Object> testResult = new java.util.LinkedHashMap<String, Object>();
    testResult.put("success", false);
    testResult.put("testClass", "com.example.DemoTest");
    testResult.put("total", 2);
    testResult.put("failed", 1);

    Path reportFile = SimpleIde.writeTestReport(tempDir, "com.example.DemoTest", testResult);
    // 验证：返回的带时间戳报告文件存在且为常规文件
    assertTrue(Files.isRegularFile(reportFile));
    // 验证：最新报告固定文件 test-report.json 存在
    assertTrue(Files.isRegularFile(tempDir.resolve(".simple-ide/reports/test-report.json")));
    // 验证：testResult 注入了 reportFile 绝对路径
    assertEquals(reportFile.toAbsolutePath().toString(), testResult.get("reportFile"));

    // 验证：报告文件内容为完整 JSON，包含测试类与失败统计
    String content = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
    // 验证：包含失败用例统计数字
    assertTrue(content.contains("\"failed\":1"));
    // 验证：包含目标测试类名
    assertTrue(content.contains("com.example.DemoTest"));
  }

  /** 验证从 JSON 字符串中注入 reportFile 并成功写入落盘文件。 */
  @Test
  void attach_report_file_and_write_injects_report_file() throws Exception {
    Path tempDir = Files.createTempDirectory("simple-ide-attach-test");
    String rawJson = "{\"success\":false,\"testClass\":\"com.example.FooTest\",\"failed\":3}";
    String finalJson = SimpleIde.attachReportFileAndWrite(tempDir, "com.example.FooTest", rawJson);

    // 验证：返回的 JSON 中注入了 reportFile 字段
    assertTrue(finalJson.contains("\"reportFile\":"));
    // 验证：固定测试报告文件被创建
    Path latestReport = tempDir.resolve(".simple-ide/reports/test-report.json");
    assertTrue(Files.isRegularFile(latestReport));
    // 验证：写入文件的内容与返回的 finalJson 一致
    String content = new String(Files.readAllBytes(latestReport), StandardCharsets.UTF_8);
    assertEquals(finalJson, content);
  }

  /** 验证 MCP 模式在有多个失败用例时，完整保留并暴露所有失败方法名与堆栈。 */
  @Test
  void parses_multiple_failed_teamcity_test_results_with_full_details() {
    String stack1 = "org.junit.ComparisonFailure: expected:<[a]> but was:<[b]>\n"
      + "\tat com.example.DemoTest.testFail1(DemoTest.java:42)\n";
    String stack2 = "java.lang.NullPointerException: NPE occurred\n"
      + "\tat com.example.DemoTest.testFail2(DemoTest.java:55)\n";

    String rawOutput = "##teamcity[enteredTheMatrix]\n"
      + "##teamcity[testStarted name='DemoTest.testFail1']\n"
      + "##teamcity[testFailed name='DemoTest.testFail1' message='expected:<[a]> but was:<[b]>' details='" + stack1.replace("\n", "|n") + "']\n"
      + "##teamcity[testFinished name='DemoTest.testFail1' duration='20']\n"
      + "##teamcity[testStarted name='DemoTest.testFail2']\n"
      + "##teamcity[testFailed name='DemoTest.testFail2' message='NPE occurred' details='" + stack2.replace("\n", "|n") + "']\n"
      + "##teamcity[testFinished name='DemoTest.testFail2' duration='15']\n";

    String mcpJson = "{\"exitCode\":1,\"output\":\"" + rawOutput.replace("\n", "\\n").replace("\"", "\\\"").replace("'", "\\'") + "\"}";
    String result = SimpleIde.mcpTestResultToJson(mcpJson, "com.example.DemoTest", null);

    // 验证：测试执行状态为失败
    assertTrue(result.contains("\"success\":false"));
    // 验证：解析出总失败数为 2
    assertTrue(result.contains("\"failed\":2"));
    // 验证：包含第一个失败的方法名
    assertTrue(result.contains("DemoTest.testFail1"));
    // 验证：包含第一个失败的行号定位
    assertTrue(result.contains("DemoTest.java:42"));
    // 验证：包含第二个失败的方法名
    assertTrue(result.contains("DemoTest.testFail2"));
    // 验证：包含第二个失败的行号定位
    assertTrue(result.contains("DemoTest.java:55"));
    // 验证：包含第二个失败的异常类型
    assertTrue(result.contains("NullPointerException"));
  }

  /** 验证命令历史的记录与读取。 */
  @Test
  void records_and_reads_command_history() throws Exception {
    Path tempDir = Files.createTempDirectory("simple-ide-history-test");
    SimpleIde.recordCommand(tempDir, "build", "--project /tmp/demo --timeout 300", true, 1250, "Build succeeded in 1250ms");
    SimpleIde.recordCommand(tempDir, "test", "--project /tmp/demo --class com.example.FooTest", false, 450, "✗ 2 tests failed");

    List<Map<String, Object>> history = SimpleIde.readCommandHistory(tempDir, 10);
    // 验证：历史记录数量为 2
    assertEquals(2, history.size());
    // 验证：最新的记录排在最前面
    assertEquals("test", history.get(0).get("command"));
    // 验证：最新记录的执行状态为失败
    assertEquals(false, history.get(0).get("success"));
    // 验证：第一条记录的命令为 build
    assertEquals("build", history.get(1).get("command"));
    // 验证：第一条记录执行状态为成功
    assertEquals(true, history.get(1).get("success"));
  }

  /** 验证 generateHtmlReport 生成包含关联应用、命令历史与测试结果的完整 HTML 报告。 */
  @Test
  void generates_html_report_with_applications_history_and_results() throws Exception {
    Path tempDir = Files.createTempDirectory("simple-ide-html-test");

    // 1. 创建关联应用状态
    Path runDir = tempDir.resolve(".simple-ide/runs");
    Files.createDirectories(runDir);
    Path stateFile = runDir.resolve("OrderService.properties");
    new SimpleIde.RunState(
      12345L,
      runDir.resolve("OrderService.log"),
      8080,
      5005,
      tempDir.toString(),
      Arrays.asList("java", "-jar", "app.jar")
    ).write(stateFile);

    // 2. 创建测试报告
    Path reportDir = tempDir.resolve(".simple-ide/reports");
    Files.createDirectories(reportDir);
    String testReportJson = "{\"success\":false,\"testClass\":\"com.example.OrderTest\",\"total\":10,\"passed\":8,\"failed\":2,\"duration\":350,\"failures\":[{\"test\":\"testTimeout\",\"message\":\"Timed out waiting for ACK\"}]}";
    Files.write(reportDir.resolve("test-report.json"), testReportJson.getBytes(StandardCharsets.UTF_8));

    // 3. 记录命令历史
    SimpleIde.recordCommand(tempDir, "run", "--configuration OrderService --port 8080", true, 500, "Started on port 8080");

    // 4. 生成 HTML 报告
    Path htmlReport = SimpleIde.generateHtmlReport(tempDir);
    // 验证：HTML 文件被成功创建
    assertTrue(Files.isRegularFile(htmlReport));

    String html = new String(Files.readAllBytes(htmlReport), StandardCharsets.UTF_8);
    // 验证：HTML 包含 Simple IDE 标识
    assertTrue(html.contains("Simple IDE"));
    // 验证：HTML 包含关联应用名称 OrderService
    assertTrue(html.contains("OrderService"));
    // 验证：HTML 包含应用端口 8080
    assertTrue(html.contains("8080"));
    // 验证：HTML 包含调试端口 5005
    assertTrue(html.contains("5005"));
    // 验证：HTML 包含测试报告类名与失败统计
    assertTrue(html.contains("com.example.OrderTest"));
    assertTrue(html.contains("Timed out waiting for ACK"));
    // 验证：HTML 包含命令历史
    assertTrue(html.contains("--configuration OrderService"));
  }

  /** 验证 helpText 中包含 report 命令说明。 */
  @Test
  void describes_report_command_in_help() {
    // 验证：report 命令独立帮助文本
    assertTrue(SimpleIde.helpText("report").contains("report"));
    // 验证：帮助文本说明生成 HTML 报告
    assertTrue(SimpleIde.helpText("report").contains("HTML"));
    // 验证：全局帮助列出 report 命令
    assertTrue(SimpleIde.helpText(null).contains("report"));
  }

  /** 验证动态管理 HTTP 服务提供页面与实时 API 交互。 */
  @Test
  void dashboard_server_serves_html_and_api() throws Exception {
    Path tempDir = Files.createTempDirectory("simple-ide-server-test");

    // 1. 创建应用配置与日志
    Path runDir = tempDir.resolve(".simple-ide/runs");
    Files.createDirectories(runDir);
    Path logFile = runDir.resolve("PayService.log");
    Files.write(logFile, Arrays.asList("Spring Boot started on 9090", "Ready to serve traffic"), StandardCharsets.UTF_8);
    Path stateFile = runDir.resolve("PayService.properties");
    new SimpleIde.RunState(
      99999L,
      logFile,
      9090,
      5005,
      tempDir.toString(),
      Arrays.asList("java", "-jar", "pay.jar")
    ).write(stateFile);

    // 2. 写入测试报告与命令历史
    Path reportDir = tempDir.resolve(".simple-ide/reports");
    Files.createDirectories(reportDir);
    Files.write(reportDir.resolve("test-report.json"), "{\"success\":true,\"testClass\":\"com.example.PayTest\",\"total\":5,\"passed\":5,\"failed\":0,\"duration\":120}".getBytes(StandardCharsets.UTF_8));
    SimpleIde.recordCommand(tempDir, "build", "--project /tmp/pay", true, 800, "Build OK");

    // 3. 启动管理服务
    int port = SimpleIde.findAvailablePort();
    SimpleIde.DashboardServer server = SimpleIde.startDashboardServer(tempDir, port);
    try {
      // 4. 请求 GET /
      java.net.HttpURLConnection connHtml = (java.net.HttpURLConnection) new java.net.URL("http://localhost:" + port + "/").openConnection();
      // 验证：首页返回 HTTP 200
      assertEquals(200, connHtml.getResponseCode());
      String html = new String(SimpleIde.readAllBytes(connHtml.getInputStream()), StandardCharsets.UTF_8);
      // 验证：首页包含动态管理控制台标识
      assertTrue(html.contains("Simple IDE"));
      // 验证：首页包含应用名称
      assertTrue(html.contains("PayService"));
      // 验证：首页包含自动轮询与 API 调用逻辑
      assertTrue(html.contains("/api/status"));

      // 5. 请求 GET /api/status
      java.net.HttpURLConnection connStatus = (java.net.HttpURLConnection) new java.net.URL("http://localhost:" + port + "/api/status").openConnection();
      // 验证：API 状态接口返回 HTTP 200
      assertEquals(200, connStatus.getResponseCode());
      String statusJson = new String(SimpleIde.readAllBytes(connStatus.getInputStream()), StandardCharsets.UTF_8);
      // 验证：JSON 包含关联应用 PayService
      assertTrue(statusJson.contains("PayService"));
      // 验证：JSON 包含测试报告 PayTest
      assertTrue(statusJson.contains("PayTest"));
      // 验证：JSON 包含历史命令 build
      assertTrue(statusJson.contains("build"));

      // 6. 请求 GET /api/logs
      java.net.HttpURLConnection connLogs = (java.net.HttpURLConnection) new java.net.URL("http://localhost:" + port + "/api/logs?configuration=PayService&tail=10").openConnection();
      // 验证：日志接口返回 HTTP 200
      assertEquals(200, connLogs.getResponseCode());
      String logsJson = new String(SimpleIde.readAllBytes(connLogs.getInputStream()), StandardCharsets.UTF_8);
      // 验证：日志接口成功返回日志文本内容
      assertTrue(logsJson.contains("Spring Boot started on 9090"));
    } finally {
      server.stop();
    }
  }

  /** 验证 helpText 中已移除 open 命令并增加了 list 命令说明。 */
  @Test
  void describes_list_command_and_removes_open_in_help() {
    String globalHelp = SimpleIde.helpText(null);
    // 验证：全局帮助中不再包含 open 命令
    assertFalse(globalHelp.contains("  open    "));
    // 验证：全局帮助中包含 list 命令
    assertTrue(globalHelp.contains("  list    "));

    // 验证：list 命令独立帮助文本包含用法说明
    String listHelp = SimpleIde.helpText("list");
    // 验证：list 帮助文本包含项目参数
    assertTrue(listHelp.contains("--project"));
    // 验证：list 帮助文本包含集中管控说明
    assertTrue(listHelp.contains("集中管控") || listHelp.contains("列出"));

    // 验证：单独查询 open 帮助返回未知命令
    String openHelp = SimpleIde.helpText("open");
    // 验证：open 不再作为合法命令
    assertTrue(openHelp.contains("未知命令"));
  }

  /** 验证 list 命令能够跨项目集中收集所有启动的应用。 */
  @Test
  void list_apps_across_projects() throws Exception {
    Path projectA = Files.createTempDirectory("project-a");
    Path projectB = Files.createTempDirectory("project-b");

    // 1. 在 projectA 下写入 Java 应用状态
    Path runDirA = projectA.resolve(".simple-ide/runs");
    Files.createDirectories(runDirA);
    Path logA = runDirA.resolve("OrderServiceA.log");
    Files.write(logA, Arrays.asList("Order Service Started"), StandardCharsets.UTF_8);
    Path stateA = runDirA.resolve("OrderServiceA.properties");
    new SimpleIde.RunState(11111L, logA, 8081, 5005, projectA.toString(), Arrays.asList("java", "-jar", "order.jar")).write(stateA);

    // 2. 在 projectB 下写入 npm 服务状态
    Path runDirB = projectB.resolve(".simple-ide/runs");
    Files.createDirectories(runDirB);
    Path logB = runDirB.resolve("npm-dev-b.log");
    Files.write(logB, Arrays.asList("Vite ready on port 3000"), StandardCharsets.UTF_8);
    Path stateB = runDirB.resolve("npm-dev-b.properties");
    new SimpleIde.RunState(22222L, logB, 3000, -1, projectB.toString(), Arrays.asList("npm", "run", "dev")).write(stateB);

    // 3. 注册两个项目到全局注册表
    SimpleIde.registerProject(projectA);
    SimpleIde.registerProject(projectB);

    // 4. 执行 list 查询全局应用
    SimpleIde.Command cmdGlobal = SimpleIde.Command.parse(new String[]{"list"});
    Map<String, Object> globalResult = SimpleIde.listApps(cmdGlobal);

    // 验证：全局 list 调用成功
    assertEquals(Boolean.TRUE, globalResult.get("success"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> apps = (List<Map<String, Object>>) globalResult.get("apps");
    // 验证：包含至少 2 个应用
    assertTrue(apps.size() >= 2);

    boolean foundOrder = false;
    boolean foundNpm = false;
    for (Map<String, Object> app : apps) {
      if ("OrderServiceA".equals(app.get("configuration"))) {
        foundOrder = true;
        // 验证：OrderServiceA 类型识别为 java
        assertEquals("java", app.get("type"));
        // 验证：OrderServiceA 端口为 8081
        assertEquals(8081, app.get("port"));
        // 验证：OrderServiceA 调试端口为 5005
        assertEquals(5005, app.get("debugPort"));
      } else if ("npm-dev-b".equals(app.get("configuration"))) {
        foundNpm = true;
        // 验证：npm-dev-b 类型识别为 npm
        assertEquals("npm", app.get("type"));
        // 验证：npm-dev-b 端口为 3000
        assertEquals(3000, app.get("port"));
      }
    }
    // 验证：全局清单中成功匹配到 OrderServiceA
    assertTrue(foundOrder);
    // 验证：全局清单中成功匹配到 npm-dev-b
    assertTrue(foundNpm);

    // 5. 指定 --project 时仅返回该项目的应用
    SimpleIde.Command cmdProjectA = SimpleIde.Command.parse(new String[]{"list", "--project", projectA.toString()});
    Map<String, Object> resultA = SimpleIde.listApps(cmdProjectA);
    // 验证：指定项目 A 查询成功
    assertEquals(Boolean.TRUE, resultA.get("success"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> appsA = (List<Map<String, Object>>) resultA.get("apps");
    // 验证：项目 A 仅有 1 个应用
    assertEquals(1, appsA.size());
    // 验证：该应用为 OrderServiceA
    assertEquals("OrderServiceA", appsA.get(0).get("configuration"));
  }

  /** 验证 list 命令能够返回正在使用 simple-ide 的活跃工程目录清单。 */
  @Test
  void list_includes_active_projects_summary() throws Exception {
    Path project = Files.createTempDirectory("project-active-dirs");

    // 1. 在项目中写入运行中应用配置
    Path runDir = project.resolve(".simple-ide/runs");
    Files.createDirectories(runDir);
    Path logFile = runDir.resolve("PayApp.log");
    Files.write(logFile, Arrays.asList("Started PayApp"), StandardCharsets.UTF_8);
    Path stateFile = runDir.resolve("PayApp.properties");
    new SimpleIde.RunState(88888L, logFile, 8099, -1, project.toString(), Arrays.asList("java", "-jar", "pay.jar")).write(stateFile);

    // 2. 显式注册该项目
    SimpleIde.registerProject(project);

    // 3. 执行 list 命令
    SimpleIde.Command cmd = SimpleIde.Command.parse(new String[]{"list"});
    Map<String, Object> result = SimpleIde.listApps(cmd);

    // 验证：返回结果成功
    assertEquals(Boolean.TRUE, result.get("success"));
    // 验证：返回结果中包含 activeProjects 列表
    assertTrue(result.containsKey("activeProjects"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> activeProjects = (List<Map<String, Object>>) result.get("activeProjects");
    // 验证：activeProjects 列表非空
    assertFalse(activeProjects.isEmpty());

    boolean foundProject = false;
    for (Map<String, Object> projInfo : activeProjects) {
      if (project.toAbsolutePath().toString().equals(projInfo.get("projectPath"))) {
        foundProject = true;
        // 验证：项目元数据包含应用数量统计
        assertTrue(projInfo.containsKey("totalApps"));
      }
    }
    // 验证：在活跃项目列表中找到了当前测试项目
    assertTrue(foundProject);
  }

  /** 验证 discoverActiveProjects 在跨平台或非 Linux 环境下安全执行不抛出任何异常。 */
  @Test
  void discover_active_projects_is_safe_across_platforms() {
    Map<Path, List<Map<String, Object>>> cliOut = new LinkedHashMap<Path, List<Map<String, Object>>>();
    // 验证：调用进程探测安全执行，绝不抛出任何异常
    assertDoesNotThrow(() -> {
      Set<Path> paths = SimpleIde.discoverActiveProjects(cliOut);
      // 验证：返回结果不为 null
      assertNotNull(paths);
    });
  }

  /** 验证动态管理控制台即使在未启动应用的工程中打开，也能集中展示全系统其他工程启动的应用与实时日志。 */
  @Test
  void dashboard_server_discovers_cross_project_apps() throws Exception {
    Path emptyProject = Files.createTempDirectory("dashboard-empty-proj");
    Path appProject = Files.createTempDirectory("dashboard-app-proj");

    // 1. 在 appProject 下写入一个应用
    Path runDir = appProject.resolve(".simple-ide/runs");
    Files.createDirectories(runDir);
    Path logFile = runDir.resolve("CrossService.log");
    Files.write(logFile, Arrays.asList("Cross service listening on 7070"), StandardCharsets.UTF_8);
    Path stateFile = runDir.resolve("CrossService.properties");
    new SimpleIde.RunState(77777L, logFile, 7070, -1, appProject.toString(), Arrays.asList("java", "-jar", "cross.jar")).write(stateFile);
    SimpleIde.registerProject(appProject);

    // 2. 在空工程 emptyProject 下启动 DashboardServer
    int port = SimpleIde.findAvailablePort();
    SimpleIde.DashboardServer server = SimpleIde.startDashboardServer(emptyProject, port);
    try {
      // 3. 请求 GET /api/status
      java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL("http://localhost:" + port + "/api/status").openConnection();
      // 验证：状态接口响应 HTTP 200
      assertEquals(200, conn.getResponseCode());
      String json = new String(SimpleIde.readAllBytes(conn.getInputStream()), StandardCharsets.UTF_8);
      // 验证：在空工程的控制台接口中成功返回了外部工程的 CrossService
      assertTrue(json.contains("CrossService"));
      // 验证：返回结果包含了外部工程路径
      assertTrue(json.contains(appProject.toAbsolutePath().toString()));

      // 4. 请求 GET /api/logs，带 projectPath
      java.net.HttpURLConnection logConn = (java.net.HttpURLConnection) new java.net.URL("http://localhost:" + port + "/api/logs?configuration=CrossService&projectPath=" + java.net.URLEncoder.encode(appProject.toAbsolutePath().toString(), "UTF-8")).openConnection();
      // 验证：日志接口响应 HTTP 200
      assertEquals(200, logConn.getResponseCode());
      String logsJson = new String(SimpleIde.readAllBytes(logConn.getInputStream()), StandardCharsets.UTF_8);
      // 验证：成功获取外部工程的日志内容
      assertTrue(logsJson.contains("Cross service listening on 7070"));
    } finally {
      server.stop();
    }
  }

  /** 验证 extractProjectFromCmdline 能够正确提取 --project 与 -Duser.dir 路径。 */
  @Test
  void extract_project_from_cmdline_parses_path() throws Exception {
    Path tempDir = Files.createTempDirectory("extract-cmdline-test");
    String cmdWithProject = "java SimpleIde run --project " + tempDir.toAbsolutePath();
    Path extracted1 = SimpleIde.extractProjectFromCmdline(cmdWithProject);
    // 验证：成功从 --project 提取出有效项目路径
    assertNotNull(extracted1);
    // 验证：提取的项目路径与预期一致
    assertEquals(tempDir.toAbsolutePath().normalize(), extracted1);

    String cmdWithUserDir = "java -Duser.dir=" + tempDir.toAbsolutePath() + " Application";
    Path extracted2 = SimpleIde.extractProjectFromCmdline(cmdWithUserDir);
    // 验证：成功从 -Duser.dir 提取出有效项目路径
    assertNotNull(extracted2);
    // 验证：提取的 user.dir 路径与预期一致
    assertEquals(tempDir.toAbsolutePath().normalize(), extracted2);
  }

  /** 验证控制台停止接口禁止 GET 请求并返回 HTTP 405 Method Not Allowed。 */
  @Test
  void dashboard_server_stop_api_rejects_get_requests() throws Exception {
    Path project = Files.createTempDirectory("dashboard-stop-sec-test");
    int port = SimpleIde.findAvailablePort();
    SimpleIde.DashboardServer server = SimpleIde.startDashboardServer(project, port);
    try {
      java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL("http://127.0.0.1:" + port + "/api/stop?configuration=App").openConnection();
      conn.setRequestMethod("GET");
      // 验证：GET 请求被安全拦截并返回 405
      assertEquals(405, conn.getResponseCode());
    } finally {
      server.stop();
    }
  }
}





