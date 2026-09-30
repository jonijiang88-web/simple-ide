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

  /**
   * 校验 IDEA MCP 实例解析出的目标项目与请求一致。
   * 插件在「请求路径嵌套于另一个已打开项目内」时会退化为父项目，
   * 此时编译会落到父项目（可能是无源根的 EMPTY_MODULE）并返回伪成功，因此必须提前拦截。
   *
   * @param mcp     已连接的 MCP 客户端
   * @param project 请求的目标项目路径
   * @return 校验通过时原样返回传入的客户端
   */
  static McpClient verifyMcpTarget(McpClient mcp, Path project) throws IOException {
    String expected = project.toAbsolutePath().normalize().toString();
    String actual;
    try {
      actual = extractProjectBasePath(mcp.callToolViaSse("mcp_ext_status", mapOf("projectPath", expected)));
    }
    catch (IOException e) {
      throw new IOException("无法确认 IDEA MCP 的目标项目(projectPath=" + expected + "): " + e.getMessage(), e);
    }
    checkMcpTarget(expected, actual);
    return mcp;
  }

  /**
   * 校验请求路径与 MCP 宿主路径是否精确一致；不一致或无法确认时抛出异常，避免伪成功。
   *
   * @param expected 请求的目标项目绝对路径（已规范化）
   * @param actual   MCP 解析出的宿主项目 base 路径，无法解析时为 null
   */
  static void checkMcpTarget(String expected, String actual) throws IOException {
    if (actual == null || actual.isEmpty()) {
      throw new IOException("无法确认 IDEA MCP 的目标项目(projectPath=" + expected + ")，已拒绝编译以避免误编其他项目");
    }
    if (!actual.equals(expected)) {
      throw new IOException("IDEA MCP 目标项目与请求不一致：请求 " + expected + "，实际 " + actual
        + "。常见原因是请求路径嵌套在另一个已打开项目内，插件会退化为父项目；"
        + "请关闭父项目，或用 IJ_MCP_SERVER_PORT 指向目标项目所在 IDEA 实例的端口");
    }
  }

  /** 从 mcp_ext_status 响应中解析宿主项目 base 路径；响应中不含该字段时返回 null。 */
  static String extractProjectBasePath(String mcpResponse) {
    if (mcpResponse == null) return null;
    return extractJsonString(unwrapMcpContent(mcpResponse), "projectBasePath");
  }

  /** 列出 IDEA MCP Server 上所有可用工具。 */
  private static void tools(Command command) throws Exception {
    Path project = command.project();
    McpClient mcp = connectMcp(project);
    if (mcp == null) throw new IOException("IDEA MCP 不可用");
    String result = mcp.listTools();
    System.out.println(result);
  }

  /** 通过 IDEA MCP 执行项目编译。返回编译状态；返回 null 表示 MCP 不可用。 */
  private static BuildStatus buildViaMcp(Path project, List<String> modules, List<String> explicitFiles, int timeoutSec, boolean autoRebuild) throws IOException, InterruptedException {
    McpClient mcp = connectMcp(project);
    if (mcp == null) return null;

    // 编译必须保证目标项目正确：MCP 在嵌套项目场景会把路径解析到父项目并返回伪成功
    verifyMcpTarget(mcp, project);

    if (!modules.isEmpty()) {
      // build_project 仅支持按文件（filesToRebuild）限定范围，不支持按模块编译；显式提示避免误以为 --module 生效
      System.err.println("[warn] --module 在当前 IDEA MCP 后端不生效（build_project 不支持按模块编译），将按项目范围增量编译：" + modules);
    }

    BuildStatus status = performBuildViaMcp(mcp, project, explicitFiles, timeoutSec, autoRebuild);
    System.out.println(status.jsonResult);
    return status;
  }

  /** 执行 IDEA MCP 编译并返回构建状态与标准 JSON 结果。 */
  static BuildStatus performBuildViaMcp(McpClient mcp, Path project, List<String> explicitFiles, int timeoutSec, boolean autoRebuild) throws IOException {
    String projectPathStr = project.toAbsolutePath().normalize().toString();

    // 编译前先触发 IDEA VFS 同步，确保外部修改的文件被 IDEA 内部感知
    try {
      mcp.callToolViaSse("git_status", mapOf("projectPath", projectPathStr));
    }
    catch (Exception e) {
      // 不再静默吞掉：同步失败意味着项目路径或 VFS 状态异常，需要让调用方看见
      System.err.println("[warn] 编译前 VFS 同步失败: " + e.getMessage());
    }

    Map<String, Object> args = new LinkedHashMap<String, Object>();
    args.put("projectPath", projectPathStr);
    args.put("rebuild", autoRebuild);
    args.put("timeout", timeoutSec * 1000);

    // IDEA 的增量仅依据 VFS 事件，外部（命令行）改动可能不被感知，导致漏编并返回伪成功；
    // 因此默认把 git 改动文件显式交给 filesToRebuild 强制纳入编译（--file 显式指定时以其为准）。
    List<String> filesToRebuild = resolveFilesToRebuild(project, explicitFiles);
    if (!filesToRebuild.isEmpty()) {
      args.put("filesToRebuild", filesToRebuild);
    }

    String result = mcp.callToolViaSse("build_project", args);
    String buildJson = mcpResultToBuildJson(result);
    boolean success = buildJson.contains("\"success\":true") || buildJson.contains("\"success\": true");

    // 编译成功后，对本次 git 修改的源文件检查无用变量/声明提示（Unused declaration）
    if (success) {
      try {
        List<String> modifiedFiles = !explicitFiles.isEmpty() ? explicitFiles : collectGitModifiedFiles(project);
        if (!modifiedFiles.isEmpty()) {
          List<Map<String, Object>> unused = queryUnusedDiagnosticsForModifiedFiles(mcp, projectPathStr, modifiedFiles);
          buildJson = appendUnusedToBuildJson(buildJson, unused);
        }
      }
      catch (Exception ignored) {}
    }

    return new BuildStatus(success, buildJson);
  }

  /** 格式化测试前置编译失败时的 JSON 响应。 */
  static String formatTestPreBuildFailure(String testClass, String method, String buildJson) {
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("success", false);
    result.put("testClass", testClass);
    if (method != null) result.put("method", method);
    result.put("exitCode", 1);
    result.put("error", "测试前置编译失败");
    result.put("buildResult", buildJson);
    result.put("engine", "idea-mcp");
    return json(result);
  }

  /**
   * 决定本次编译要显式纳入的文件列表。
   * 显式 --file 优先；否则取 git 工作区改动文件（相对项目根，符合 filesToRebuild 契约）。
   * 目的是绕开「IDEA 仅依赖 VFS 事件判断增量、从而漏编外部改动并返回伪成功」的行为。
   *
   * @param project       目标项目根目录
   * @param explicitFiles 用户显式指定的文件（--file），可为空
   * @return 需要强制编译的相对路径列表，可能为空
   */
  static List<String> resolveFilesToRebuild(Path project, List<String> explicitFiles) {
    if (explicitFiles != null && !explicitFiles.isEmpty()) {
      Path base = project.toAbsolutePath().normalize();
      List<String> relative = new ArrayList<String>(explicitFiles.size());
      for (String file : explicitFiles) {
        relative.add(toProjectRelative(base, file));
      }
      return relative;
    }
    return collectGitModifiedFiles(project);
  }

  /**
   * 将文件路径归一化为相对项目根的路径（filesToRebuild 契约要求相对路径）。
   * 路径不在项目内或无法解析时原样返回，避免丢失用户输入。
   *
   * @param base 项目根绝对路径
   * @param file 用户传入的文件路径（绝对路径或相对当前工作目录）
   * @return 相对项目根的路径（'/' 分隔）
   */
  static String toProjectRelative(Path base, String file) {
    if (file == null || file.isEmpty()) return file;
    try {
      Path path = Paths.get(file);
      if (!path.isAbsolute()) {
        path = base.resolve(path);
      }
      path = path.normalize();
      if (path.startsWith(base)) {
        return base.relativize(path).toString().replace(File.separatorChar, '/');
      }
    }
    catch (Exception ignored) {}
    return file;
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
      // 不再静默：git 不可用意味着本次无法强制纳入外部改动，需要让调用方看见
      System.err.println("[warn] 无法读取 git 改动文件，本次编译不会强制纳入外部改动: " + e.getMessage());
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
      // git rename 输出形如 "old -> new"，只保留新路径
      int arrow = filePath.indexOf(" -> ");
      if (arrow >= 0) {
        filePath = filePath.substring(arrow + 4).trim();
      }
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

  /** 将 MCP build_project / build 返回转换为 simple-ide 标准 build JSON（忽略 WARNING 告警以减少 Token 消耗）。 */
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
      }
      else {
        problems.add(mapOf("kind", "ERROR", "message", "IDEA MCP 编译失败: " + content));
      }
    }

    boolean finalSuccess = isSuccess && !timedOut && (problems.isEmpty() || problems.stream().noneMatch(p -> "ERROR".equals(p.get("kind"))));
    return json(mapOf("success", finalSuccess, "problems", problems, "engine", "idea-mcp"));
  }

  /** 判断诊断消息是否属于无用声明或死代码（Unused declaration / 未使用 / 未分配 / 冗余初始值等）。 */
  static boolean isUnusedDeclarationMessage(String message) {
    if (message == null || message.isEmpty()) return false;
    String lower = message.toLowerCase();
    return message.contains("从未使用")
      || message.contains("从未分配")
      || message.contains("从未使用变量")
      || message.contains("冗余")
      || lower.contains("never used")
      || lower.contains("never assigned")
      || lower.contains("redundant")
      || lower.contains("unused declaration");
  }

  /** 从 MCP get_aggregated_diagnostics 返回的 JSON 中提取指定改动文件的无用声明列表。 */
  static List<Map<String, Object>> extractUnusedDiagnosticsFromMcp(String mcpDiagnosticsJson, List<String> modifiedFiles, String projectBasePath) {
    List<Map<String, Object>> unusedList = new ArrayList<Map<String, Object>>();
    if (mcpDiagnosticsJson == null || mcpDiagnosticsJson.isEmpty() || modifiedFiles == null || modifiedFiles.isEmpty()) {
      return unusedList;
    }

    String content = unwrapMcpContent(mcpDiagnosticsJson);
    int idx = content.indexOf("\"top_problems\"");
    if (idx < 0) return unusedList;
    int bracketStart = content.indexOf('[', idx);
    if (bracketStart < 0) return unusedList;
    int bracketEnd = content.indexOf(']', bracketStart);
    if (bracketEnd <= bracketStart) return unusedList;
    String arrayContent = content.substring(bracketStart + 1, bracketEnd).trim();
    if (arrayContent.isEmpty()) return unusedList;

    // 规范化改动文件集合以便快速比对
    java.util.Set<String> normalizedModifiedFiles = new java.util.HashSet<String>();
    for (String mf : modifiedFiles) {
      String n = mf.replace('\\', '/');
      if (n.startsWith("./")) n = n.substring(2);
      normalizedModifiedFiles.add(n);
    }

    String normBase = projectBasePath != null ? projectBasePath.replace('\\', '/') : "";
    if (normBase.endsWith("/")) normBase = normBase.substring(0, normBase.length() - 1);

    int pos = 0;
    while (pos < arrayContent.length()) {
      int objStart = arrayContent.indexOf('{', pos);
      if (objStart < 0) break;
      int objEnd = arrayContent.indexOf('}', objStart);
      if (objEnd < 0) break;
      String objStr = arrayContent.substring(objStart, objEnd + 1);

      String rawFile = extractJsonString(objStr, "file");
      String msg = extractJsonString(objStr, "message");
      String lineStr = extractJsonNumber(objStr, "line");

      if (rawFile != null && msg != null && isUnusedDeclarationMessage(msg)) {
        String normFile = rawFile.replace('\\', '/');
        String relFile = normFile;
        if (!normBase.isEmpty() && normFile.startsWith(normBase + "/")) {
          relFile = normFile.substring(normBase.length() + 1);
        }

        // 仅匹配本次改动的源文件（支持绝对路径或相对路径匹配）
        if (normalizedModifiedFiles.contains(relFile) || normalizedModifiedFiles.contains(normFile)) {
          Map<String, Object> entry = new LinkedHashMap<String, Object>();
          entry.put("file", relFile);
          if (lineStr != null) {
            try { entry.put("line", Integer.parseInt(lineStr)); } catch (NumberFormatException ignored) {}
          }
          entry.put("message", msg);
          unusedList.add(entry);
        }
      }

      pos = objEnd + 1;
    }

    return unusedList;
  }

  /** 将 unused 告警列表追加进编译响应 JSON 中。 */
  static String appendUnusedToBuildJson(String buildJson, List<Map<String, Object>> unusedList) {
    if (unusedList == null || unusedList.isEmpty()) return buildJson;
    int lastBrace = buildJson.lastIndexOf('}');
    if (lastBrace < 0) return buildJson;
    return buildJson.substring(0, lastBrace) + ",\"unused\":" + json(unusedList) + "}";
  }

  /** 查询 IDEA MCP 聚合诊断中属于本次改动文件的无用声明提示。 */
  private static List<Map<String, Object>> queryUnusedDiagnosticsForModifiedFiles(McpClient mcp, String projectPath, List<String> modifiedFiles) {
    List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
    try {
      Map<String, Object> diagArgs = new LinkedHashMap<String, Object>();
      diagArgs.put("projectPath", projectPath);
      diagArgs.put("scope", "project");
      diagArgs.put("severity", "warning");
      diagArgs.put("limit_per_module", 500);

      String diagResponse = mcp.callToolViaSse("get_aggregated_diagnostics", diagArgs);
      result = extractUnusedDiagnosticsFromMcp(diagResponse, modifiedFiles, projectPath);
    }
    catch (Exception ignored) {}
    return result;
  }

  /** 从 JSON 中解析 problems 列表（忽略 WARNING 级别告警以减少 Token 消耗，仅保留 ERROR）。 */
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
      String kind = extractJsonString(objStr, "kind");
      // 忽略 WARNING 级别的告警信息，仅保留 ERROR 等错误，降低上下文 Token 消耗
      if ("WARNING".equalsIgnoreCase(kind)) {
        pos = objEnd + 1;
        continue;
      }
      Map<String, Object> prob = new LinkedHashMap<String, Object>();
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

  /** 反转义标准 JSON 字符串。 */
  static String unescapeJsonString(String s) {
    if (s == null) return null;
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length()) {
        char next = s.charAt(i + 1);
        if (next == '"') sb.append('"');
        else if (next == '\\') sb.append('\\');
        else if (next == 'n') sb.append('\n');
        else if (next == 'r') sb.append('\r');
        else if (next == 't') sb.append('\t');
        else if (next == '/') sb.append('/');
        else sb.append(next);
        i++;
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /** 从 JSON 中提取指定字符串字段值。 */
  static String extractJsonString(String json, String field) {
    String key = "\"" + field + "\":\"";
    int idx = json.indexOf(key);
    if (idx < 0) return null;
    int start = idx + key.length();
    int end = start;
    while (end < json.length()) {
      if (json.charAt(end) == '"') {
        int backslashes = 0;
        int p = end - 1;
        while (p >= start && json.charAt(p) == '\\') {
          backslashes++;
          p--;
        }
        if (backslashes % 2 == 0) break;
      }
      end++;
    }
    return unescapeJsonString(json.substring(start, end));
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
      if (result.contains("\"isError\":true") || result.contains("\"isError\": true")) {
        throw new IOException("IDEA MCP execute_run_configuration 失败: " + content);
      }
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

  /** 执行编译：获取本地排队锁排队，优先 IDEA MCP，失败则 fallback 到本地进程。 */
  private static void build(Command command) throws Exception {
    Path project = command.project();
    boolean skipLock = command.flag("no-lock") || command.flag("skip-lock");
    if (skipLock) {
      executeBuild(command, project);
      return;
    }
    try (BuildLock ignored = BuildLock.acquire(project)) {
      executeBuild(command, project);
    }
  }

  /** 执行实际编译逻辑；MCP 不可用时抛出异常，编译失败时以非零退出码结束。 */
  private static void executeBuild(Command command, Path project) throws Exception {
    List<String> modules = command.values("module");
    List<String> files = command.values("file");
    int timeoutSec = Integer.parseInt(command.option("timeout", "1800"));
    boolean autoRebuild = command.flag("auto-rebuild") || command.flag("rebuild");
    BuildStatus status = buildViaMcp(project, modules, files, timeoutSec, autoRebuild);
    if (status == null) {
      throw new IllegalStateException("IDEA MCP 不可用，请确认 IDEA 已打开项目且 idea-mcp-ext 插件已安装");
    }
    if (!status.success) {
      // 编译失败必须以非零退出码结束，否则调用方仅凭退出码会把失败误判为成功
      System.exit(1);
    }
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

  /** 通过 IDEA MCP 执行测试。测试前先强制进行编译校验，防止运行陈旧 class 文件。支持 JUnit 4/5，IDEA 自动识别。 */
  private static void test(Command command) throws Exception {
    Path project = command.project();
    McpClient mcp = connectMcp(project);
    if (mcp == null) throw new IOException("IDEA MCP 不可用，无法运行测试");

    // 测试依赖正确的编译目标，先校验再执行前置编译
    verifyMcpTarget(mcp, project);

    String testClass = command.required("class");
    String method = command.option("method", null);

    // 将全限定类名转为文件路径
    String filePath = "src/test/java/" + testClass.replace('.', '/') + ".java";
    Path testFile = project.resolve(filePath);
    if (!Files.isRegularFile(testFile)) throw new IOException("测试文件不存在：" + testFile);

    // 前置步骤：强制执行全局增量编译校验，防止运行陈旧未更新的 class 文件
    if (!command.flag("skip-build")) {
      int timeoutSec = Integer.parseInt(command.option("timeout", "300"));
      boolean skipLock = command.flag("no-lock") || command.flag("skip-lock");
      BuildStatus buildStatus;
      if (skipLock) {
        buildStatus = performBuildViaMcp(mcp, project, new ArrayList<String>(), timeoutSec, false);
      } else {
        try (BuildLock ignored = BuildLock.acquire(project)) {
          buildStatus = performBuildViaMcp(mcp, project, new ArrayList<String>(), timeoutSec, false);
        }
      }
      if (!buildStatus.success) {
        System.out.println(formatTestPreBuildFailure(testClass, method, buildStatus.jsonResult));
        return;
      }
    }

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

  /** 单个测试用例执行结果。 */
  static class TestCaseInfo {
    final String name;
    String status = "PASSED";
    long duration = 0;
    String message;
    String details;
    String location;

    TestCaseInfo(String name) {
      this.name = name;
    }
  }

  /** 解析 TeamCity 转义值。 */
  static String unescapeTeamCity(String val) {
    if (val == null || val.isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < val.length(); i++) {
      char c = val.charAt(i);
      if (c == '|' && i + 1 < val.length()) {
        char next = val.charAt(i + 1);
        if (next == 'n') sb.append('\n');
        else if (next == 'r') sb.append('\r');
        else if (next == '\'') sb.append('\'');
        else if (next == '[') sb.append('[');
        else if (next == ']') sb.append(']');
        else if (next == '|') sb.append('|');
        else sb.append(next);
        i++;
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /** 从 TeamCity 协议行中解析属性键值对。 */
  static Map<String, String> parseTeamCityAttrs(String line) {
    Map<String, String> attrs = new LinkedHashMap<String, String>();
    int start = line.indexOf(' ');
    if (start < 0) return attrs;
    int pos = start + 1;
    while (pos < line.length()) {
      int eq = line.indexOf('=', pos);
      if (eq < 0) break;
      String key = line.substring(pos, eq).trim();
      int q1 = line.indexOf('\'', eq);
      if (q1 < 0) break;
      int q2 = q1 + 1;
      while (q2 < line.length()) {
        if (line.charAt(q2) == '\'') {
          int pipeCount = 0;
          int back = q2 - 1;
          while (back >= q1 && line.charAt(back) == '|') {
            pipeCount++;
            back--;
          }
          if (pipeCount % 2 == 0) break;
        }
        q2++;
      }
      if (q2 > line.length()) q2 = line.length();
      String rawVal = line.substring(q1 + 1, Math.min(q2, line.length()));
      attrs.put(key, unescapeTeamCity(rawVal));
      pos = q2 + 1;
    }
    return attrs;
  }

  /** 从异常 details 中提取测试失败的用户源码定位（如 DemoTest.java:42）。 */
  static String extractFailureLocation(String details, String testClass) {
    if (details == null || details.isEmpty()) return null;
    String simpleClass = testClass != null && testClass.contains(".")
      ? testClass.substring(testClass.lastIndexOf('.') + 1)
      : testClass;

    String fallbackLocation = null;
    for (String line : details.split("\n")) {
      String trimmed = line.trim();
      if (!trimmed.startsWith("at ")) continue;
      int openParen = trimmed.indexOf('(');
      int closeParen = trimmed.indexOf(')', openParen);
      if (openParen > 0 && closeParen > openParen) {
        String loc = trimmed.substring(openParen + 1, closeParen);
        if (simpleClass != null && (trimmed.contains(simpleClass) || loc.startsWith(simpleClass))) {
          return loc;
        }
        if (fallbackLocation == null
          && !trimmed.contains("java.base")
          && !trimmed.contains("sun.reflect")
          && !trimmed.contains("jdk.internal")
          && !trimmed.contains("org.junit")
          && !trimmed.contains("org.mockito")
          && !trimmed.contains("org.springframework")) {
          fallbackLocation = loc;
        }
      }
    }
    return fallbackLocation;
  }

  /** 判断一行日志是否属于 Logback 或测试框架的内部诊断杂音。 */
  static boolean isDiagnosticNoise(String line) {
    String trimmed = line.trim();
    if (trimmed.startsWith("##teamcity[")) return true;
    if (trimmed.contains("|-INFO in ch.qos.logback") || trimmed.contains("|-WARN in ch.qos.logback") || trimmed.contains("|-ERROR in ch.qos.logback")) return true;
    if (trimmed.contains("ch.qos.logback.core.joran") || trimmed.contains("ch.qos.logback.classic.joran")) return true;
    return false;
  }

  /** 将 MCP 测试执行结果转换为 simple-ide 标准 JSON，深度解析 TeamCity 事件并提取关键失败与过滤噪音。 */
  static String mcpTestResultToJson(String content, String testClass, String method) {
    // 1. 提取 exitCode
    int exitCode = -1;
    int ecIdx = content.indexOf("\"exitCode\":");
    if (ecIdx >= 0) {
      int valStart = ecIdx + 11;
      int valEnd = valStart;
      while (valEnd < content.length() && (Character.isDigit(content.charAt(valEnd)) || content.charAt(valEnd) == '-')) valEnd++;
      try { exitCode = Integer.parseInt(content.substring(valStart, valEnd).trim()); } catch (NumberFormatException ignored) {}
    }

    // 2. 优先尝试从 fullOutputPath 读取未被截断的完整控制台输出
    String rawOutput = null;
    String fullOutputPath = extractJsonString(content, "fullOutputPath");
    if (fullOutputPath != null && !fullOutputPath.isEmpty()) {
      try {
        Path fullPath = Paths.get(fullOutputPath);
        if (Files.isRegularFile(fullPath)) {
          rawOutput = new String(Files.readAllBytes(fullPath), StandardCharsets.UTF_8);
        }
      } catch (Exception ignored) {}
    }

    // 回退：从 content 中提取 output 字段
    if (rawOutput == null) {
      String fallback = extractJsonString(content, "output");
      rawOutput = fallback != null ? fallback : "";
    }

    // 3. 解析 TeamCity 事件与纯净业务日志
    Map<String, TestCaseInfo> testCases = new LinkedHashMap<String, TestCaseInfo>();
    List<String> cleanLogLines = new ArrayList<String>();
    long suiteDuration = 0;

    for (String line : rawOutput.split("\n")) {
      String trimmed = line.trim();
      if (trimmed.startsWith("##teamcity[")) {
        if (trimmed.startsWith("##teamcity[testStarted")) {
          Map<String, String> attrs = parseTeamCityAttrs(trimmed);
          String name = attrs.get("name");
          if (name != null && !testCases.containsKey(name)) {
            testCases.put(name, new TestCaseInfo(name));
          }
        } else if (trimmed.startsWith("##teamcity[testFinished")) {
          Map<String, String> attrs = parseTeamCityAttrs(trimmed);
          String name = attrs.get("name");
          if (name != null) {
            TestCaseInfo tc = testCases.get(name);
            if (tc == null) {
              tc = new TestCaseInfo(name);
              testCases.put(name, tc);
            }
            String durStr = attrs.get("duration");
            if (durStr != null) {
              try { tc.duration = Long.parseLong(durStr); } catch (NumberFormatException ignored) {}
            }
          }
        } else if (trimmed.startsWith("##teamcity[testFailed")) {
          Map<String, String> attrs = parseTeamCityAttrs(trimmed);
          String name = attrs.get("name");
          if (name != null) {
            TestCaseInfo tc = testCases.get(name);
            if (tc == null) {
              tc = new TestCaseInfo(name);
              testCases.put(name, tc);
            }
            tc.status = "FAILED";
            tc.message = attrs.get("message");
            tc.details = attrs.get("details");
            tc.location = extractFailureLocation(tc.details, testClass);
          }
        } else if (trimmed.startsWith("##teamcity[testIgnored")) {
          Map<String, String> attrs = parseTeamCityAttrs(trimmed);
          String name = attrs.get("name");
          if (name != null) {
            TestCaseInfo tc = testCases.get(name);
            if (tc == null) {
              tc = new TestCaseInfo(name);
              testCases.put(name, tc);
            }
            tc.status = "IGNORED";
            tc.message = attrs.get("message");
          }
        } else if (trimmed.startsWith("##teamcity[testSuiteFinished")) {
          Map<String, String> attrs = parseTeamCityAttrs(trimmed);
          String durStr = attrs.get("duration");
          if (durStr != null) {
            try { suiteDuration = Math.max(suiteDuration, Long.parseLong(durStr)); } catch (NumberFormatException ignored) {}
          }
        }
      } else if (!isDiagnosticNoise(trimmed)) {
        if (!trimmed.isEmpty()) {
          cleanLogLines.add(line);
        }
      }
    }

    // 4. 统计状态
    int total = testCases.size();
    int passed = 0;
    int failed = 0;
    int ignored = 0;
    long totalCaseDuration = 0;

    List<Map<String, Object>> failureList = new ArrayList<Map<String, Object>>();
    List<Map<String, Object>> testList = new ArrayList<Map<String, Object>>();

    for (TestCaseInfo tc : testCases.values()) {
      totalCaseDuration += tc.duration;
      Map<String, Object> tcMap = new LinkedHashMap<String, Object>();
      tcMap.put("name", tc.name);
      tcMap.put("status", tc.status);
      tcMap.put("duration", tc.duration);
      if ("PASSED".equals(tc.status)) {
        passed++;
      } else if ("FAILED".equals(tc.status)) {
        failed++;
        if (tc.message != null) tcMap.put("error", tc.message);
        Map<String, Object> failObj = new LinkedHashMap<String, Object>();
        failObj.put("test", tc.name);
        if (tc.message != null) failObj.put("message", tc.message);
        if (tc.location != null) failObj.put("location", tc.location);
        if (tc.details != null) failObj.put("details", tc.details);
        failureList.add(failObj);
      } else if ("IGNORED".equals(tc.status)) {
        ignored++;
      }
      testList.add(tcMap);
    }

    long finalDuration = suiteDuration > 0 ? suiteDuration : totalCaseDuration;
    boolean success = total > 0 ? (failed == 0 && exitCode == 0) : (exitCode == 0);

    // 5. 生成精炼 output
    StringBuilder outBuilder = new StringBuilder();
    if (success) {
      outBuilder.append("✓ All ").append(passed).append(" tests passed");
      if (finalDuration > 0) outBuilder.append(" (").append(finalDuration).append("ms)");
      if (!cleanLogLines.isEmpty()) {
        String remainingLogs = String.join("\n", cleanLogLines);
        if (remainingLogs.length() <= 2000) {
          outBuilder.append("\n\n").append(remainingLogs);
        }
      }
    } else {
      outBuilder.append("✗ Test Failed: ").append(failed).append("/").append(total).append(" tests failed");
      if (finalDuration > 0) outBuilder.append(" (").append(finalDuration).append("ms)");
      for (Map<String, Object> fail : failureList) {
        outBuilder.append("\n\n--- Failure: ").append(fail.get("test")).append(" ---");
        if (fail.containsKey("location")) outBuilder.append("\nLocation: ").append(fail.get("location"));
        if (fail.containsKey("message")) outBuilder.append("\nMessage: ").append(fail.get("message"));
        if (fail.containsKey("details")) {
          outBuilder.append("\nDetails:\n").append(fail.get("details"));
        }
      }
      if (!cleanLogLines.isEmpty() && failureList.isEmpty()) {
        outBuilder.append("\n\n").append(String.join("\n", cleanLogLines));
      }
    }

    // 6. 构造最终 JSON
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("success", success);
    result.put("testClass", testClass);
    if (method != null) result.put("method", method);
    result.put("exitCode", exitCode);
    if (total > 0) {
      result.put("total", total);
      result.put("passed", passed);
      result.put("failed", failed);
      result.put("ignored", ignored);
      result.put("duration", finalDuration);
    }
    if (!failureList.isEmpty()) {
      result.put("failures", failureList);
    }
    if (!testList.isEmpty()) {
      result.put("tests", testList);
    }
    result.put("output", outBuilder.toString());
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
    if ("build".equals(command)) return "用法: simple-ide build --project <path> [--module <name>] [--no-lock]\n\n通过 IDEA MCP 编译，需 IDEA 已打开项目且安装 idea-mcp-ext 插件。默认启用本地构建排队锁，避免并发编译资源竞争。\n";
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
  static Map<String, Object> mapOf(Object... keyValuePairs) {
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

  /** 表示 IDEA 构建执行的成功状态与诊断结果。 */
  static final class BuildStatus {
    final boolean success;
    final String jsonResult;

    BuildStatus(boolean success, String jsonResult) {
      this.success = success;
      this.jsonResult = jsonResult;
    }
  }
}
