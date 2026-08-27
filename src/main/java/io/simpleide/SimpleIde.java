package io.simpleide;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 通过 IDEA MCP 编译和管理 IDEA 项目的最小命令行工具。 */
public final class SimpleIde {
  /** 默认 IDEA MCP SSE 端口。 */
  static final int DEFAULT_MCP_PORT = 64342;
  /** 等待 IDEA 启动的最大秒数。 */
  private static final int IDEA_STARTUP_TIMEOUT_SEC = 120;

  /** 禁止实例化命令行入口类。 */
  private SimpleIde() {
  }

  /** 解析命令并输出稳定 JSON。 */
  public static void main(String[] arguments) {
    try {
      if (arguments.length > 0 && "npm".equals(arguments[0])) {
        if (arguments.length == 1 || isHelpRequest(Arrays.copyOfRange(arguments, 1, arguments.length))) {
          String action = arguments.length > 1 && !arguments[1].startsWith("-") ? arguments[1] : null;
          System.out.print(helpText(action == null ? "npm" : "npm " + action));
          return;
        }
        npm(Command.parse(Arrays.copyOfRange(arguments, 1, arguments.length)));
        return;
      }
      if (isHelpRequest(arguments)) {
        String helpCommand = "help".equals(arguments.length == 0 ? null : arguments[0]) && arguments.length > 1 ? arguments[1] : arguments.length > 1 ? arguments[0] : null;
        System.out.print(helpText(helpCommand));
        return;
      }
      Command command = Command.parse(arguments);
      if ("build".equals(command.name)) build(command);
      else if ("run".equals(command.name)) run(command);
      else if ("test".equals(command.name)) test(command);
      else if ("status".equals(command.name)) status(command);
      else if ("logs".equals(command.name)) logs(command);
      else if ("stop".equals(command.name)) stop(command);
      else if ("open".equals(command.name)) open(command);
      else if ("tools".equals(command.name)) tools(command);
      else throw new IllegalArgumentException("不支持的命令：" + command.name);
    }
    catch (Exception error) {
      System.out.println(json(mapOf("success", false, "error", error.getMessage())));
      System.exit(1);
    }
  }

  /** 获取 MCP 端口，优先从环境变量读取。 */
  static int mcpPort() {
    String env = System.getenv("IJ_MCP_SERVER_PORT");
    if (env != null && !env.trim().isEmpty()) {
      try { return Integer.parseInt(env.trim()); } catch (NumberFormatException ignored) {}
    }
    return DEFAULT_MCP_PORT;
  }

  /** 检查 IDEA MCP 是否可用，不可用则尝试启动 IDEA。返回 null 表示无法连接。 */
  private static McpClient connectMcp(Path project) throws IOException, InterruptedException {
    int port = mcpPort();
    if (McpClient.isAvailable(port)) return new McpClient(port);
    if (!McpClient.ensureIdeaRunning(project, port, IDEA_STARTUP_TIMEOUT_SEC)) return null;
    return new McpClient(port);
  }

  /** 列出 IDEA MCP Server 上所有可用工具。 */
  private static void tools(Command command) throws Exception {
    Path project = command.project();
    McpClient mcp = connectMcp(project);
    if (mcp == null) throw new IOException("IDEA MCP 不可用");
    String result = mcp.listTools();
    System.out.println(result);
  }

  /** 通过 IDEA MCP 执行项目编译。返回 true 表示成功。 */
  private static boolean buildViaMcp(Path project, List<String> modules, List<String> explicitFiles, int timeoutSec, boolean autoRebuild) throws IOException, InterruptedException {
    McpClient mcp = connectMcp(project);
    if (mcp == null) return false;

    String projectPathStr = project.toAbsolutePath().normalize().toString();

    // 编译前先触发 IDEA VFS 同步，确保外部修改的文件被 IDEA 内部感知
    try {
      mcp.callToolViaSse("git_status", mapOf("projectPath", projectPathStr));
    }
    catch (Exception ignored) {}

    Map<String, Object> args = new LinkedHashMap<String, Object>();
    args.put("projectPath", projectPathStr);
    args.put("rebuild", autoRebuild);
    args.put("timeout", timeoutSec * 1000);

    // 仅在用户显式指定 --file 时才限制编译范围；默认由 IDEA JPS 依赖图自动做全局增量级联编译
    if (!explicitFiles.isEmpty()) {
      args.put("filesToRebuild", explicitFiles);
    }

    try {
      String result = mcp.callToolViaSse("build_project", args);
      System.out.println(mcpResultToBuildJson(result));
      return true;
    }
    catch (IOException e) {
      System.err.println("IDEA MCP 编译失败，降级到 JPS: " + e.getMessage());
      return false;
    }
  }

  /** 获取 git 工作区中修改或新增的源文件相对路径列表。 */
  static List<String> collectGitModifiedFiles(Path project) {
    try {
      Process process = new ProcessBuilder("git", "status", "--porcelain")
        .directory(project.toFile())
        .redirectErrorStream(true)
        .start();
      java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
      byte[] data = new byte[1024];
      int n;
      while ((n = process.getInputStream().read(data, 0, data.length)) != -1) {
        buffer.write(data, 0, n);
      }
      process.waitFor();
      return parseGitStatusOutput(buffer.toString(StandardCharsets.UTF_8.name()));
    }
    catch (Exception e) {
      return new ArrayList<String>();
    }
  }

  /** 从 git status porcelain 输出解析有效源文件相对路径列表。 */
  static List<String> parseGitStatusOutput(String output) {
    List<String> result = new ArrayList<String>();
    if (output == null || output.isEmpty()) return result;
    String[] lines = output.split("\n");
    for (String rawLine : lines) {
      if (rawLine.length() < 4) continue;
      String status = rawLine.substring(0, 2);
      if (status.contains("D")) continue;
      String filePath = rawLine.substring(3).trim();
      if (filePath.startsWith("\"") && filePath.endsWith("\"")) {
        filePath = filePath.substring(1, filePath.length() - 1);
      }
      if (isSourceFile(filePath)) {
        result.add(filePath);
      }
    }
    return result;
  }

  /** 判断文件是否为 Java/Kotlin/Groovy 等需编译的源码文件。 */
  private static boolean isSourceFile(String path) {
    return path.endsWith(".java") || path.endsWith(".kt") || path.endsWith(".groovy") || path.endsWith(".scala");
  }

  /** 从 IDEA 模块文件中猜测主模块名。 */
  private static String guessMainModule(Path project) {
    try {
      Path modulesXml = project.resolve(".idea/modules.xml");
      if (!Files.isRegularFile(modulesXml)) throw new IOException("无 modules.xml");
      org.w3c.dom.Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(modulesXml.toFile());
      NodeList modules = doc.getElementsByTagName("module");
      if (modules.getLength() == 0) throw new IOException("modules.xml 中无模块");
      String fileUrl = ((Element) modules.item(0)).getAttribute("fileurl");
      // fileurl 格式: $PROJECT_DIR$/xxx.iml → 提取 xxx（不含 .iml）
      String name = ((Element) modules.item(0)).getAttribute("name");
      if (name != null && !name.isEmpty()) return name;
      // fallback: 从 fileurl 提取
      String filePath = ((Element) modules.item(0)).getAttribute("filepath");
      if (filePath != null) {
        String fileName = filePath.replace("$PROJECT_DIR$/", "");
        return fileName.replace(".iml", "");
      }
      throw new IOException("无法从 modules.xml 解析模块名");
    }
    catch (Exception e) {
      // fallback: 用项目目录名
      return project.getFileName().toString();
    }
  }

  /** 从 MCP 响应中提取实际的 JSON 内容（处理 content[].text 包装）。 */
  static String unwrapMcpContent(String mcpResponse) {
    // MCP tool result 格式: {"content":[{"text":"...","type":"text"}],"isError":false}
    // 提取 content[0].text 中的文本
    int textStart = mcpResponse.indexOf("\"text\":\"");
    if (textStart < 0) return mcpResponse;
    textStart += 8;
    int textEnd = textStart;
    while (textEnd < mcpResponse.length()) {
      if (mcpResponse.charAt(textEnd) == '"' && mcpResponse.charAt(textEnd - 1) != '\\') break;
      textEnd++;
    }
    String text = mcpResponse.substring(textStart, textEnd);
    // 反转义 JSON 字符串
    text = text.replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\");
    return text;
  }

  /** 将 MCP build_project / build 返回转换为 simple-ide 标准 build JSON。 */
  static String mcpResultToBuildJson(String mcpResult) {
    String content = unwrapMcpContent(mcpResult);
    boolean isSuccess = content.contains("\"isSuccess\":true") || content.contains("\"isSuccess\": true")
      || content.contains("\"ok\":true") || content.contains("\"ok\": true");
    boolean timedOut = content.contains("\"timedOut\":true") || content.contains("\"timedOut\": true");

    List<Map<String, Object>> problems = new ArrayList<Map<String, Object>>();
    if (timedOut) {
      problems.add(mapOf("kind", "ERROR", "message", "IDEA MCP 编译超时"));
    }

    List<Map<String, Object>> extracted = extractProblems(content);
    if (!extracted.isEmpty()) {
      problems.addAll(extracted);
    }
    else if (!isSuccess && !timedOut) {
      List<String> errors = extractStringArray(content, "errors");
      if (!errors.isEmpty()) {
        for (String err : errors) problems.add(mapOf("kind", "ERROR", "message", err));
        List<String> warnings = extractStringArray(content, "warnings");
        for (String w : warnings) problems.add(mapOf("kind", "WARNING", "message", w));
      }
      else {
        problems.add(mapOf("kind", "ERROR", "message", "IDEA MCP 编译失败: " + content));
      }
    }

    boolean finalSuccess = isSuccess && !timedOut && (problems.isEmpty() || problems.stream().noneMatch(p -> "ERROR".equals(p.get("kind"))));
    return json(mapOf("success", finalSuccess, "problems", problems, "engine", "idea-mcp"));
  }

  /** 从 JSON 中解析 problems 列表。 */
  static List<Map<String, Object>> extractProblems(String json) {
    List<Map<String, Object>> problems = new ArrayList<Map<String, Object>>();
    int idx = json.indexOf("\"problems\"");
    if (idx < 0) return problems;
    int bracketStart = json.indexOf('[', idx);
    if (bracketStart < 0) return problems;
    int bracketEnd = json.lastIndexOf(']');
    if (bracketEnd <= bracketStart) return problems;
    String arrayContent = json.substring(bracketStart + 1, bracketEnd).trim();
    if (arrayContent.isEmpty()) return problems;

    int pos = 0;
    while (pos < arrayContent.length()) {
      int objStart = arrayContent.indexOf('{', pos);
      if (objStart < 0) break;
      int objEnd = arrayContent.indexOf('}', objStart);
      if (objEnd < 0) break;
      String objStr = arrayContent.substring(objStart, objEnd + 1);
      Map<String, Object> prob = new LinkedHashMap<String, Object>();
      String kind = extractJsonString(objStr, "kind");
      prob.put("kind", kind != null ? kind : "ERROR");
      String file = extractJsonString(objStr, "file");
      if (file != null) prob.put("file", file);
      String lineStr = extractJsonNumber(objStr, "line");
      if (lineStr != null) {
        try { prob.put("line", Integer.parseInt(lineStr)); } catch (NumberFormatException ignored) {}
      }
      String colStr = extractJsonNumber(objStr, "column");
      if (colStr != null) {
        try { prob.put("column", Integer.parseInt(colStr)); } catch (NumberFormatException ignored) {}
      }
      String msg = extractJsonString(objStr, "message");
      prob.put("message", msg != null ? msg : "");
      problems.add(prob);
      pos = objEnd + 1;
    }
    return problems;
  }

  /** 从 JSON 中提取指定数字字段值字符串。 */
  private static String extractJsonNumber(String json, String field) {
    String key = "\"" + field + "\":";
    int idx = json.indexOf(key);
    if (idx < 0) return null;
    int start = idx + key.length();
    while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
    if (start >= json.length()) return null;
    int end = start;
    while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
    return start == end ? null : json.substring(start, end);
  }

  /** 从 JSON 中提取指定字段的字符串数组。 */
  private static List<String> extractStringArray(String json, String field) {
    List<String> result = new ArrayList<String>();
    String key = "\"" + field + "\"";
    int idx = json.indexOf(key);
    if (idx < 0) return result;
    int bracketStart = json.indexOf('[', idx);
    if (bracketStart < 0) return result;
    int bracketEnd = json.indexOf(']', bracketStart);
    if (bracketEnd < 0) return result;
    String arrayContent = json.substring(bracketStart + 1, bracketEnd).trim();
    if (arrayContent.isEmpty()) return result;
    int pos = 0;
    while (pos < arrayContent.length()) {
      int quoteStart = arrayContent.indexOf('"', pos);
      if (quoteStart < 0) break;
      int quoteEnd = quoteStart + 1;
      while (quoteEnd < arrayContent.length()) {
        if (arrayContent.charAt(quoteEnd) == '"' && arrayContent.charAt(quoteEnd - 1) != '\\') break;
        quoteEnd++;
      }
      result.add(arrayContent.substring(quoteStart + 1, quoteEnd).replace("\\\"", "\""));
      pos = quoteEnd + 1;
    }
    return result;
  }

  /** 从 JSON 中提取指定字符串字段值。 */
  private static String extractJsonString(String json, String field) {
    String key = "\"" + field + "\":\"";
    int idx = json.indexOf(key);
    if (idx < 0) return null;
    int start = idx + key.length();
    int end = start;
    while (end < json.length()) {
      if (json.charAt(end) == '"' && json.charAt(end - 1) != '\\') break;
      end++;
    }
    return json.substring(start, end).replace("\\\"", "\"").replace("\\\\", "\\");
  }

  /** 通过 IDEA MCP execute_run_configuration 启动运行配置并支持动态端口覆盖。 */
  private static boolean runViaMcp(Path project, String configName, int port, Command command) throws IOException, InterruptedException {
    McpClient mcp = connectMcp(project);
    if (mcp == null) return false;

    Map<String, Object> args = new LinkedHashMap<String, Object>();
    args.put("projectPath", project.toString());
    args.put("configurationName", configName);
    args.put("waitForExit", false);
    if (port > 0) {
      args.put("programArguments", "--server.port=" + port);
    }

    try {
      String result = mcp.callToolViaSse("execute_run_configuration", args);
      String content = unwrapMcpContent(result);
      String fullOutputPath = extractJsonString(content, "fullOutputPath");
      Path logPath = fullOutputPath != null && !fullOutputPath.isEmpty()
        ? Paths.get(fullOutputPath)
        : project.resolve(".simple-ide/runs/" + configName + ".log");

      Path stateFile = runState(project, configName);
      List<String> cmdList = new ArrayList<String>();
      cmdList.add("idea-mcp");
      cmdList.add("execute_run_configuration");
      cmdList.add(configName);
      if (port > 0) cmdList.add("--server.port=" + port);

      RunState state = new RunState(0, logPath, port, project.toString(), cmdList);
      state.write(stateFile);

      Map<String, Object> resp = new LinkedHashMap<String, Object>();
      resp.put("projectPath", project.toString());
      resp.put("configuration", configName);
      resp.put("engine", "idea-mcp");
      resp.put("status", port > 0 ? "STARTING" : "RUNNING");
      resp.put("running", true);
      if (port > 0) resp.put("port", port);
      resp.put("log", logPath.toString());
      System.out.println(json(resp));
      return true;
    }
    catch (IOException e) {
      System.err.println("IDEA MCP 运行启动失败，降级到本地: " + e.getMessage());
      return false;
    }
  }

  /** 通过 IDEA MCP control_run_configuration 控制运行配置（停止/状态查询）。 */
  private static boolean runControlViaMcp(Path project, String configName, String action) throws IOException, InterruptedException {
    McpClient mcp = connectMcp(project);
    if (mcp == null) return false;

    Map<String, Object> args = new LinkedHashMap<String, Object>();
    args.put("projectPath", project.toString());
    args.put("name", configName);
    args.put("action", action);

    try {
      String result = mcp.callToolViaSse("control_run_configuration", args);
      System.out.println(mcpRunResultToJson(result, project, configName, action));
      return true;
    }
    catch (IOException e) {
      System.err.println("IDEA MCP 运行控制失败，降级到本地: " + e.getMessage());
      return false;
    }
  }

  /** 将 MCP control_run_configuration 返回转换为 simple-ide 标准 JSON。 */
  private static String mcpRunResultToJson(String mcpResult, Path project, String configName, String action) {
    String content = unwrapMcpContent(mcpResult);
    boolean ok = content.contains("\"ok\":true") || content.contains("\"ok\": true");
    if (!ok) return json(mapOf("success", false, "error", "IDEA MCP " + action + " 失败: " + content));

    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("projectPath", project.toString());
    result.put("configuration", configName);
    result.put("engine", "idea-mcp");

    if ("start".equals(action)) {
      result.put("status", "RUNNING");
      result.put("running", true);
    }
    else if ("stop".equals(action)) {
      result.put("stopped", true);
    }
    else if ("status".equals(action)) {
      boolean running = content.contains("\"health\":\"STARTING\"") || content.contains("\"health\": \"STARTING\"")
        || content.contains("\"health\":\"RUNNING\"") || content.contains("\"health\": \"RUNNING\"");
      result.put("status", running ? "RUNNING" : "STOPPED");
      result.put("running", running);
    }
    return json(result);
  }

  // ==================== CLI 命令 ====================

  /** 执行编译：优先 IDEA MCP，失败则 fallback 到本地进程。 */
  private static void build(Command command) throws Exception {
    Path project = command.project();
    List<String> modules = command.values("module");
    List<String> files = command.values("file");
    int timeoutSec = Integer.parseInt(command.option("timeout", "1800"));
    boolean autoRebuild = command.flag("auto-rebuild") || command.flag("rebuild");
    if (buildViaMcp(project, modules, files, timeoutSec, autoRebuild)) return;
    throw new IllegalStateException("IDEA MCP 不可用，请确认 IDEA 已打开项目且 idea-mcp-ext 插件已安装");
  }

  /** 启动 Application：优先 IDEA MCP（支持 --random-port / --port 动态覆盖），失败则本地进程启动。 */
  private static void run(Command command) throws Exception {
    Path project = command.project();
    String configName = command.required("configuration");
    int port = configuredPort(command);

    if (runViaMcp(project, configName, port, command)) return;

    // fallback: 本地进程启动（读取 runConfiguration XML）
    ApplicationConfiguration configuration = ApplicationConfiguration.load(project, configName);
    Path stateFile = runState(project, configuration.name);
    RunState current = RunState.read(stateFile);
    if (current != null && isProcessAlive(current.pid)) throw new IllegalStateException("Application 已在运行，PID=" + current.pid);
    List<String> processCommand = new ArrayList<String>();
    processCommand.add("setsid");
    processCommand.add(command.option("java", "java"));
    processCommand.addAll(split(configuration.vmParameters));
    Path runDirectory = project.resolve(".simple-ide/runs");
    Files.createDirectories(runDirectory);
    processCommand.add(configuration.mainClass);
    if (port > 0) processCommand.add("--server.port=" + port);
    processCommand.addAll(split(configuration.programParameters));
    Path log = runDirectory.resolve(configuration.name + ".log");
    Process process = new ProcessBuilder(processCommand)
      .directory(configuration.workingDirectory(project).toFile())
      .redirectErrorStream(true)
      .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
      .start();
    RunState state = new RunState(getPid(process), log, port, project.toString(), processCommand);
    state.write(stateFile);
    System.out.println(json(state.toMap(port > 0 ? "STARTING" : "RUNNING")));
  }

  /** 查询 Application 状态。 */
  private static void status(Command command) throws Exception {
    Path project = command.project();
    String configName = command.required("configuration");

    Path stateFile = runState(project, configName);
    RunState state = RunState.read(stateFile);
    if (state != null) {
      if (state.port > 0) {
        boolean portOpen = isPortOpen(state.port);
        String st = portOpen ? "RUNNING" : "STARTING";
        Map<String, Object> map = state.toMap(st);
        map.put("engine", state.pid > 0 ? "local" : "idea-mcp");
        System.out.println(json(map));
        return;
      }
      if (state.pid > 0 && isProcessAlive(state.pid)) {
        System.out.println(json(state.toMap("RUNNING")));
        return;
      }
    }

    if (runControlViaMcp(project, configName, "status")) return;

    if (stateFile != null) Files.deleteIfExists(stateFile);
    System.out.println(json(mapOf("projectPath", project.toString(), "status", "STOPPED", "running", false)));
  }

  /** 停止 Application。 */
  private static void stop(Command command) throws Exception {
    Path project = command.project();
    String configName = command.required("configuration");
    Path stateFile = runState(project, configName);
    RunState state = RunState.read(stateFile);

    try {
      McpClient mcp = connectMcp(project);
      if (mcp != null) {
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("projectPath", project.toString());
        args.put("name", configName);
        args.put("action", "stop");
        mcp.callToolViaSse("control_run_configuration", args);
      }
    }
    catch (Exception ignored) {}

    if (state != null && state.pid > 0 && isProcessAlive(state.pid)) {
      signalProcessGroup(state.pid, "TERM");
      for (int attempt = 0; attempt < 50 && isProcessAlive(state.pid); attempt++) {
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
      }
      if (isProcessAlive(state.pid)) { signalProcessGroup(state.pid, "KILL"); }
    }
    Files.deleteIfExists(stateFile);
    System.out.println(json(mapOf("stopped", true, "configuration", configName, "projectPath", project.toString())));
  }

  /** 通过 IDEA MCP 执行测试。支持 JUnit 4/5，IDEA 自动识别。 */
  private static void test(Command command) throws Exception {
    Path project = command.project();
    McpClient mcp = connectMcp(project);
    if (mcp == null) throw new IOException("IDEA MCP 不可用，无法运行测试");

    String testClass = command.required("class");
    String method = command.option("method", null);

    // 将全限定类名转为文件路径
    String filePath = "src/test/java/" + testClass.replace('.', '/') + ".java";
    Path testFile = project.resolve(filePath);
    if (!Files.isRegularFile(testFile)) throw new IOException("测试文件不存在：" + testFile);

    // Step 1: 用 get_run_configurations(filePath=...) 获取 run points
    int line = findTestLine(mcp, project, filePath, testClass, method);

    // Step 2: 用 execute_run_configuration(filePath + line) 执行
    Map<String, Object> args = new LinkedHashMap<String, Object>();
    args.put("projectPath", project.toString());
    args.put("filePath", filePath);
    args.put("line", line);
    args.put("waitForExit", true);
    args.put("timeout", 300000); // 5 分钟超时

    try {
      String result = mcp.callToolViaSse("execute_run_configuration", args);
      String content = unwrapMcpContent(result);
      System.out.println(mcpTestResultToJson(content, testClass, method));
    }
    catch (IOException e) {
      throw new IOException("IDEA MCP 测试执行失败：" + e.getMessage());
    }
  }

  /** 通过 get_run_configurations 查找测试方法的行号。 */
  private static int findTestLine(McpClient mcp, Path project, String filePath, String testClass, String method) throws IOException {
    Map<String, Object> args = new LinkedHashMap<String, Object>();
    args.put("projectPath", project.toString());
    args.put("filePath", filePath);

    String result = mcp.callToolViaSse("get_run_configurations", args);
    String content = unwrapMcpContent(result);

    // 解析 runPoints 数组，找匹配的测试方法
    int rpIdx = content.indexOf("\"runPoints\"");
    if (rpIdx < 0) throw new IOException("IDEA 未在 " + filePath + " 中发现可执行入口");

    // 如果指定了 method，找匹配的 run point
    if (method != null) {
      String methodPattern = method;
      int searchFrom = rpIdx;
      while (true) {
        int lineIdx = content.indexOf("\"line\":", searchFrom);
        if (lineIdx < 0) break;
        // 检查这个 run point 的描述或 elementText 是否包含 method 名
        int blockStart = content.lastIndexOf("{", lineIdx);
        int blockEnd = findBlockEnd(content, blockStart);
        String block = content.substring(blockStart, blockEnd);
        if (block.contains(methodPattern)) {
          return extractInt(content, lineIdx + 7);
        }
        searchFrom = blockEnd;
      }
      throw new IOException("未找到测试方法：" + testClass + "." + method);
    }

    // 未指定 method，取第一个 run point（通常是类级别的 Run All Tests）
    int firstLineIdx = content.indexOf("\"line\":", rpIdx);
    if (firstLineIdx < 0) throw new IOException("IDEA 未在 " + filePath + " 中发现可执行入口");
    return extractInt(content, firstLineIdx + 7);
  }

  /** 从 JSON 字符串中提取整数值。 */
  private static int extractInt(String text, int start) {
    int pos = start;
    while (pos < text.length() && !Character.isDigit(text.charAt(pos)) && text.charAt(pos) != '-') pos++;
    int end = pos;
    while (end < text.length() && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '-')) end++;
    return Integer.parseInt(text.substring(pos, end));
  }

  /** 找到 JSON 块的结束位置。 */
  private static int findBlockEnd(String text, int start) {
    if (start < 0 || start >= text.length() || text.charAt(start) != '{') return start + 1;
    int depth = 0;
    boolean inString = false;
    boolean escaped = false;
    for (int i = start; i < text.length(); i++) {
      char c = text.charAt(i);
      if (escaped) { escaped = false; continue; }
      if (c == '\\') { escaped = true; continue; }
      if (c == '"') { inString = !inString; continue; }
      if (inString) continue;
      if (c == '{') depth++;
      else if (c == '}') { depth--; if (depth == 0) return i + 1; }
    }
    return text.length();
  }

  /** 将 MCP 测试执行结果转换为 simple-ide 标准 JSON。 */
  private static String mcpTestResultToJson(String content, String testClass, String method) {
    // 提取 exitCode
    int exitCode = -1;
    int ecIdx = content.indexOf("\"exitCode\":");
    if (ecIdx >= 0) {
      int valStart = ecIdx + 11;
      int valEnd = valStart;
      while (valEnd < content.length() && (Character.isDigit(content.charAt(valEnd)) || content.charAt(valEnd) == '-')) valEnd++;
      try { exitCode = Integer.parseInt(content.substring(valStart, valEnd).trim()); } catch (NumberFormatException ignored) {}
    }

    // 提取 output
    String output = "";
    int outIdx = content.indexOf("\"output\":\"");
    if (outIdx >= 0) {
      int vStart = outIdx + 10;
      int vEnd = vStart;
      while (vEnd < content.length()) {
        if (content.charAt(vEnd) == '"' && content.charAt(vEnd - 1) != '\\') break;
        vEnd++;
      }
      output = content.substring(vStart, vEnd).replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\");
    }

    boolean success = exitCode == 0;
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("success", success);
    result.put("testClass", testClass);
    if (method != null) result.put("method", method);
    result.put("exitCode", exitCode);
    result.put("output", output);
    result.put("engine", "idea-mcp");
    return json(result);
  }

  // ==================== npm 子命令 ====================

  /** 执行 npm 开发服务的启动、状态、日志或停止操作。 */
  private static void npm(Command command) throws IOException {
    Path project = command.project();
    String script = command.required("script");
    String configuration = "npm-" + script.replaceAll("[^A-Za-z0-9_.-]", "_");
    if ("run".equals(command.name)) npmRun(command, project, script, configuration);
    else if ("status".equals(command.name)) npmStatus(project, configuration);
    else if ("logs".equals(command.name)) npmLogs(command, project, configuration);
    else if ("stop".equals(command.name)) npmStop(project, configuration);
    else throw new IllegalArgumentException("npm 不支持的命令：" + command.name);
  }

  /** 启动 npm script。 */
  private static void npmRun(Command command, Path project, String script, String configuration) throws IOException {
    if (!Files.isRegularFile(project.resolve("package.json"))) throw new IllegalArgumentException("项目中不存在 package.json：" + project);
    Path stateFile = runState(project, configuration);
    RunState current = RunState.read(stateFile);
    if (current != null && isProcessAlive(current.pid)) throw new IllegalStateException("npm 服务已在运行，PID=" + current.pid);
    int port = configuredPort(command);
    List<String> processCommand = new ArrayList<String>();
    processCommand.add("setsid");
    processCommand.add(command.option("npm", "npm"));
    processCommand.add("run");
    processCommand.add(script);
    if (port > 0) { processCommand.add("--"); processCommand.add(command.option("port-argument", "--port")); processCommand.add(Integer.toString(port)); }
    Path runDirectory = project.resolve(".simple-ide/runs");
    Files.createDirectories(runDirectory);
    Path log = runDirectory.resolve(configuration + ".log");
    ProcessBuilder builder = new ProcessBuilder(processCommand).directory(project.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
    if (port > 0) builder.environment().put("PORT", Integer.toString(port));
    for (String assignment : command.values("env")) {
      int separator = assignment.indexOf('=');
      if (separator < 1) throw new IllegalArgumentException("--env 必须为 KEY=VALUE：" + assignment);
      builder.environment().put(assignment.substring(0, separator), assignment.substring(separator + 1));
    }
    Process process = builder.start();
    RunState state = new RunState(getPid(process), log, port, project.toString(), processCommand);
    state.write(stateFile);
    System.out.println(json(state.toMap(port > 0 ? "STARTING" : "RUNNING")));
  }

  /** 查询 npm 服务状态。 */
  private static void npmStatus(Path project, String configuration) throws IOException {
    Path stateFile = runState(project, configuration);
    RunState state = RunState.read(stateFile);
    if (state == null || !isProcessAlive(state.pid)) {
      Files.deleteIfExists(stateFile);
      System.out.println(json(mapOf("projectPath", project.toString(), "status", "STOPPED", "running", false)));
      return;
    }
    System.out.println(json(state.toMap(state.port > 0 && isPortOpen(state.port) ? "RUNNING" : "STARTING")));
  }

  /** 返回 npm 服务日志。 */
  private static void npmLogs(Command command, Path project, String configuration) throws IOException {
    int lines = Integer.parseInt(command.option("tail", "200"));
    if (lines < 1) throw new IllegalArgumentException("--tail 必须大于 0");
    Path log = project.resolve(".simple-ide/runs/" + configuration + ".log");
    System.out.println(json(mapOf("projectPath", project.toString(), "configuration", configuration, "logPath", log.toString(), "lines", tail(log, lines))));
  }

  /** 停止 npm 服务。 */
  private static void npmStop(Path project, String configuration) throws IOException {
    Path stateFile = runState(project, configuration);
    RunState state = RunState.read(stateFile);
    if (state == null || !isProcessAlive(state.pid)) {
      Files.deleteIfExists(stateFile);
      System.out.println(json(mapOf("stopped", false)));
      return;
    }
    signalProcessGroup(state.pid, "TERM");
    for (int attempt = 0; attempt < 50 && isProcessAlive(state.pid); attempt++) {
      try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
    }
    if (isProcessAlive(state.pid)) signalProcessGroup(state.pid, "KILL");
    Files.deleteIfExists(stateFile);
    System.out.println(json(mapOf("stopped", true, "pid", state.pid)));
  }

  // ==================== 日志 ====================

  /** 返回 Application 日志尾部。 */
  private static void logs(Command command) throws IOException {
    Path project = command.project();
    String configuration = command.required("configuration");
    int lines = Integer.parseInt(command.option("tail", "200"));
    if (lines < 1) throw new IllegalArgumentException("--tail 必须大于 0");

    Path stateFile = runState(project, configuration);
    RunState state = RunState.read(stateFile);
    Path log = state != null && state.log != null && Files.isRegularFile(state.log)
      ? state.log
      : project.resolve(".simple-ide/runs/" + configuration + ".log");

    if (!Files.isRegularFile(log)) {
      Path artemisDevLog = project.resolve("artemis-dev.log");
      if (Files.isRegularFile(artemisDevLog)) log = artemisDevLog;
    }
    System.out.println(json(mapOf("projectPath", project.toString(), "configuration", configuration, "logPath", log.toString(), "lines", tail(log, lines))));
  }

  /** 在 IDEA 中打开项目，IDEA 未运行时以独立后台进程启动并等待 MCP 端口就绪。 */
  private static void open(Command command) throws Exception {
    Path project = command.project();
    int port = mcpPort();

    // 确保以 setsid 独立后台会话方式调用 idea 命令行打开目标项目窗口，避免阻塞或被父进程生命周期影响
    new ProcessBuilder("setsid", "idea", project.toString())
      .redirectInput(new File("/dev/null"))
      .redirectOutput(new File("/dev/null"))
      .redirectError(new File("/dev/null"))
      .start();

    // IDEA 已运行且 MCP 可达
    if (McpClient.isAvailable(port)) {
      System.out.println(json(mapOf("success", true, "projectPath", project.toString(), "ideaRunning", true, "mcpPort", port)));
      return;
    }

    System.out.println("正在启动 IDEA 并打开项目...");

    // 等待 MCP 端口就绪
    for (int i = 0; i < IDEA_STARTUP_TIMEOUT_SEC; i++) {
      Thread.sleep(1000);
      if (McpClient.isAvailable(port)) {
        System.out.println(json(mapOf("success", true, "projectPath", project.toString(), "ideaRunning", true, "mcpPort", port, "startupWaitSec", i + 1)));
        return;
      }
    }
    throw new IOException("IDEA 启动超时（" + IDEA_STARTUP_TIMEOUT_SEC + "秒），MCP 端口 " + port + " 未就绪");
  }

  // ==================== 工具方法 ====================

  /** 获取 Process PID（兼容 JDK 9+ 与 JDK 8）。 */
  private static long getPid(Process process) {
    try {
      return (long) Process.class.getMethod("pid").invoke(process);
    }
    catch (Throwable ignored) {
      try {
        Field pidField = process.getClass().getDeclaredField("pid");
        pidField.setAccessible(true);
        return pidField.getLong(process);
      }
      catch (Exception e) {
        throw new RuntimeException("无法获取进程 PID", e);
      }
    }
  }

  /** 通过 kill -0 检测进程是否存活（JDK 8 兼容）。 */
  private static boolean isProcessAlive(long pid) {
    try {
      Process p = new ProcessBuilder("kill", "-0", Long.toString(pid)).redirectErrorStream(true).start();
      return p.waitFor() == 0;
    }
    catch (Exception e) { return false; }
  }

  /** 向进程组发送终止信号。 */
  private static void signalProcessGroup(long pid, String signal) throws IOException {
    try { new ProcessBuilder("/bin/kill", "-" + signal, "--", "-" + pid).start().waitFor(); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("终止进程组时被中断", e); }
  }

  /** 判断命令行是否请求帮助。 */
  private static boolean isHelpRequest(String[] arguments) {
    return arguments.length == 0 || "help".equals(arguments[0]) || "--help".equals(arguments[0]) || "-h".equals(arguments[0])
      || (arguments.length > 1 && ("--help".equals(arguments[1]) || "-h".equals(arguments[1])));
  }

  /** 返回帮助文本。 */
  static String helpText(String command) {
    if (command == null || "help".equals(command) || "--help".equals(command) || "-h".equals(command))
      return "用法: simple-ide <command> [options]\n\n命令:\n  open    在 IDEA 中打开项目（IDEA 未运行时自动启动）\n  build   使用 IDEA MCP 编译项目模块\n  run     启动 IDEA Application 配置\n  test    通过 IDEA MCP 执行测试（支持 JUnit 4/5）\n  status  查询应用状态\n  logs    查询应用日志尾部\n  stop    停止应用进程\n\n环境变量:\n  IJ_MCP_SERVER_PORT  IDEA MCP Server 端口（默认 64342）\n\n使用 `simple-ide <command> --help` 查看子命令参数。\n";
    if ("open".equals(command)) return "用法: simple-ide open --project <path>\n\n在 IDEA 中打开项目。IDEA 未运行时自动启动并等待 MCP 端口就绪。\n";
    if ("build".equals(command)) return "用法: simple-ide build --project <path> [--module <name>]\n\n通过 IDEA MCP 编译，需 IDEA 已打开项目且安装 idea-mcp-ext 插件。\n";
    if ("run".equals(command)) return "用法: simple-ide run --project <path> --configuration <name> [--random-port | --port <number>] [--java <path>]\n\n--random-port 自动选择端口并传入 --server.port。\n";
    if ("test".equals(command)) return "用法: simple-ide test --project <path> --class <FQN> [--method <name>]\n\n通过 IDEA MCP 运行测试，支持 JUnit 4/5。IDEA 自动识别测试框架。\n";
    if ("status".equals(command)) return "用法: simple-ide status --project <path> --configuration <name>\n";
    if ("logs".equals(command)) return "用法: simple-ide logs --project <path> --configuration <name> [--tail <lines>]\n";
    if ("stop".equals(command)) return "用法: simple-ide stop --project <path> --configuration <name>\n";
    if ("npm".equals(command)) return "用法: simple-ide npm <run|status|logs|stop> --project <path> --script <name> [options]\n";
    if ("npm run".equals(command)) return "用法: simple-ide npm run --project <path> --script <name> [--random-port | --port <number>] [--port-argument <flag>] [--npm <path>] [--env KEY=VALUE]\n";
    if ("npm status".equals(command)) return "用法: simple-ide npm status --project <path> --script <name>\n";
    if ("npm logs".equals(command)) return "用法: simple-ide npm logs --project <path> --script <name> [--tail <lines>]\n";
    if ("npm stop".equals(command)) return "用法: simple-ide npm stop --project <path> --script <name>\n";
    return "未知命令: " + command + "\n";
  }

  /** 读取文件末尾若干行。 */
  static List<String> tail(Path file, int limit) throws IOException {
    if (!Files.isRegularFile(file)) return new ArrayList<String>();
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    return lines.subList(Math.max(0, lines.size() - limit), lines.size());
  }

  /** 解析固定端口或分配随机端口。 */
  private static int configuredPort(Command command) throws IOException {
    if (command.flag("random-port")) return findAvailablePort();
    String value = command.option("port", null);
    return value == null ? -1 : Integer.parseInt(value);
  }

  /** 向操作系统申请一个临时空闲 TCP 端口。 */
  static int findAvailablePort() throws IOException {
    ServerSocket socket = new ServerSocket(0);
    int port = socket.getLocalPort();
    socket.close();
    return port;
  }

  /** 判断 TCP 端口是否正在监听。 */
  static boolean isPortOpen(int port) {
    if (port <= 0) return false;
    try { Socket socket = new Socket("127.0.0.1", port); socket.close(); return true; }
    catch (IOException e) { return false; }
  }

  /** 按空白分隔参数。 */
  private static List<String> split(String value) {
    if (value == null || value.trim().isEmpty()) return new ArrayList<String>();
    return new ArrayList<String>(Arrays.asList(value.trim().split("\\s+")));
  }

  /** 返回 Application 状态文件路径。 */
  private static Path runState(Path project, String configuration) {
    return project.resolve(".simple-ide/runs/" + configuration + ".properties");
  }

  /** 将基本 Java 值序列化为一行 JSON。 */
  static String json(Object value) {
    if (value == null) return "null";
    if (value instanceof String) {
      String text = (String) value;
      return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
    if (value instanceof Number || value instanceof Boolean) return value.toString();
    if (value instanceof Map) {
      Map<?, ?> map = (Map<?, ?>) value;
      StringBuilder sb = new StringBuilder("{");
      boolean first = true;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!first) sb.append(',');
        sb.append(json(entry.getKey().toString())).append(":").append(json(entry.getValue()));
        first = false;
      }
      return sb.append('}').toString();
    }
    if (value instanceof List) {
      List<?> list = (List<?>) value;
      StringBuilder sb = new StringBuilder("[");
      boolean first = true;
      for (Object item : list) {
        if (!first) sb.append(',');
        sb.append(json(item));
        first = false;
      }
      return sb.append(']').toString();
    }
    throw new IllegalArgumentException("不支持 JSON 值：" + value.getClass());
  }

  /** 构造 Map 的便捷方法（JDK 8 兼容）。 */
  private static Map<String, Object> mapOf(Object... keyValuePairs) {
    Map<String, Object> map = new LinkedHashMap<String, Object>();
    for (int i = 0; i < keyValuePairs.length; i += 2) {
      map.put((String) keyValuePairs[i], keyValuePairs[i + 1]);
    }
    return map;
  }

  // ==================== 内部类 ====================

  /** 表示已解析的命令名与参数。 */
  static final class Command {
    final String name;
    private final Map<String, List<String>> options;

    Command(String name, Map<String, List<String>> options) {
      this.name = name;
      this.options = options;
    }

    /** 解析 --key value、--key=value 和布尔标记。 */
    static Command parse(String[] arguments) {
      if (arguments.length == 0) throw new IllegalArgumentException("用法：simple-ide <build|run|status|stop> --project <目录>");
      Map<String, List<String>> options = new LinkedHashMap<String, List<String>>();
      for (int index = 1; index < arguments.length; index++) {
        String argument = arguments[index];
        if (!argument.startsWith("--")) throw new IllegalArgumentException("无效参数：" + argument);
        String[] pair = argument.substring(2).split("=", 2);
        String key = pair[0];
        String value = pair.length == 2 ? pair[1] : index + 1 < arguments.length && !arguments[index + 1].startsWith("--") ? arguments[++index] : "true";
        if (!options.containsKey(key)) options.put(key, new ArrayList<String>());
        options.get(key).add(value);
      }
      return new Command(arguments[0], options);
    }

    String required(String name) {
      String value = option(name, null);
      if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("缺少参数 --" + name);
      return value;
    }

    String option(String name, String fallback) {
      List<String> values = options.get(name);
      return values == null ? fallback : values.get(values.size() - 1);
    }

    List<String> values(String name) {
      List<String> v = options.get(name);
      return v == null ? new ArrayList<String>() : v;
    }

    boolean flag(String name) {
      return options.containsKey(name);
    }

    Path project() {
      return Paths.get(required("project")).toAbsolutePath().normalize();
    }
  }

  /** 表示 IDEA Application 配置。 */
  private static final class ApplicationConfiguration {
    final String name;
    final String mainClass;
    final String module;
    final String vmParameters;
    final String programParameters;
    final String workingDir;

    ApplicationConfiguration(String name, String mainClass, String module, String vmParameters, String programParameters, String workingDir) {
      this.name = name;
      this.mainClass = mainClass;
      this.module = module;
      this.vmParameters = vmParameters;
      this.programParameters = programParameters;
      this.workingDir = workingDir;
    }

    static ApplicationConfiguration load(Path project, String name) throws Exception {
      Path file = project.resolve(".idea/runConfigurations/" + name + ".xml");
      org.w3c.dom.Document source = Files.isRegularFile(file)
        ? DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile())
        : DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(project.resolve(".idea/workspace.xml").toFile());
      NodeList configurations = source.getElementsByTagName("configuration");
      Element configuration = null;
      for (int i = 0; i < configurations.getLength(); i++) {
        Element candidate = (Element) configurations.item(i);
        if (name.equals(candidate.getAttribute("name"))) { configuration = candidate; break; }
      }
      if (configuration == null || !"Application".equals(configuration.getAttribute("type")))
        throw new IllegalArgumentException("配置不是 Application 类型：" + name);
      Map<String, String> opts = new LinkedHashMap<String, String>();
      NodeList optionNodes = configuration.getElementsByTagName("option");
      for (int i = 0; i < optionNodes.getLength(); i++) {
        Element opt = (Element) optionNodes.item(i);
        opts.put(opt.getAttribute("name"), opt.getAttribute("value"));
      }
      Element moduleEl = (Element) configuration.getElementsByTagName("module").item(0);
      String moduleName = opts.containsKey("MODULE_NAME") ? opts.get("MODULE_NAME") : moduleEl == null ? "" : moduleEl.getAttribute("name");
      String mainClass = opts.containsKey("MAIN_CLASS_NAME") ? opts.get("MAIN_CLASS_NAME") : "";
      if (moduleName.trim().isEmpty() || mainClass.trim().isEmpty()) throw new IllegalArgumentException("配置缺少模块或主类：" + name);
      String wd = opts.containsKey("WORKING_DIRECTORY") ? opts.get("WORKING_DIRECTORY") : "$PROJECT_DIR$";
      return new ApplicationConfiguration(name, mainClass, moduleName,
        opts.containsKey("VM_PARAMETERS") ? opts.get("VM_PARAMETERS") : "",
        opts.containsKey("PROGRAM_PARAMETERS") ? opts.get("PROGRAM_PARAMETERS") : "", wd);
    }

    Path workingDirectory(Path project) {
      return Paths.get(workingDir.replace("$PROJECT_DIR$", project.toAbsolutePath().toString()).replace("$USER_HOME$", System.getProperty("user.home")));
    }
  }

  /** 表示落盘保存的进程状态。 */
  static final class RunState {
    final long pid;
    final Path log;
    final int port;
    final String projectPath;
    final List<String> command;

    RunState(long pid, Path log, int port, String projectPath, List<String> command) {
      this.pid = pid;
      this.log = log;
      this.port = port;
      this.projectPath = projectPath;
      this.command = command;
    }

    static RunState read(Path file) throws IOException {
      if (!Files.isRegularFile(file)) return null;
      List<String> values = Files.readAllLines(file, StandardCharsets.UTF_8);
      if (values.size() >= 4 && values.get(2).matches("-?\\d+")) {
        return new RunState(Long.parseLong(values.get(0)), Paths.get(values.get(1)), Integer.parseInt(values.get(2)), values.get(3), values.subList(4, values.size()));
      }
      return new RunState(Long.parseLong(values.get(0)), Paths.get(values.get(1)), -1, "", values.subList(2, values.size()));
    }

    void write(Path file) throws IOException {
      Files.createDirectories(file.getParent());
      List<String> values = new ArrayList<String>();
      values.add(Long.toString(pid));
      values.add(log.toString());
      values.add(Integer.toString(port));
      values.add(projectPath);
      values.addAll(command);
      Files.write(file, values, StandardCharsets.UTF_8);
    }

    boolean alive() {
      return isProcessAlive(pid);
    }

    Map<String, Object> toMap(String status) {
      Map<String, Object> value = new LinkedHashMap<String, Object>();
      value.put("projectPath", projectPath);
      value.put("pid", pid);
      value.put("port", port);
      value.put("status", status);
      value.put("running", true);
      value.put("log", log.toString());
      value.put("command", command);
      return value;
    }
  }
}
