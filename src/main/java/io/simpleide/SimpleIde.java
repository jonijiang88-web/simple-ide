package io.simpleide;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 编译和管理 Java 项目的最小命令行工具（默认内置 JPS 增量引擎，秒级编译，支持可选 IDEA MCP）。 */
public final class SimpleIde {
  /** 默认 IDEA MCP SSE 端口。 */
  static final int DEFAULT_MCP_PORT = 64342;
  /** 默认 JDWP 远程调试端口。 */
  static final int DEFAULT_DEBUG_PORT = 5005;
  /** 工具版本，用于构建状态与缓存指纹追溯。 */
  static final String TOOL_VERSION = "1.0.0";
  /** 依赖 Classpath 缓存格式版本，与 jps-cli 保持一致。 */
  static final String CACHE_FORMAT_VERSION = "2";
  /** 判定 npm 编译结果时最多从日志末尾读取的字节数。 */
  static final int LOG_TAIL_LIMIT_BYTES = 256 * 1024;
  /** 内置 Spring Boot DevTools jar（2.7.18 + simple-ide parent-first 补丁）。 */
  static final String DEVTOOLS_JAR_NAME = "spring-boot-devtools-2.7.18-simpleide.jar";
  /** simple-ide 方法体热替换 agent。 */
  static final String HOTSWAP_AGENT_JAR_NAME = "simple-ide-hotswap-agent.jar";
  /** 热更新触发文件与标记所在目录。 */
  static final String HOT_RELOAD_DIR = ".simple-ide/devtools";
  /** 方法体热替换工作目录。 */
  static final String HOTSWAP_DIR = ".simple-ide/hotswap";
  /** 热更新触发文件名（DevTools trigger-file 只匹配简单文件名）。 */
  static final String HOT_RELOAD_TRIGGER_NAME = "trigger";
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
      if (arguments.length > 0 && "--version".equals(arguments[0])) {
        printVersion();
        return;
      }
      Command command = Command.parse(arguments);
      long startTime = System.currentTimeMillis();
      boolean success = true;
      String summary = null;
      try {
        if ("build".equals(command.name)) { build(command); summary = "编译完成"; }
        else if ("run".equals(command.name)) { run(command); summary = "应用启动"; }
        else if ("debug".equals(command.name)) { run(command); summary = "调试启动"; }
        else if ("reload".equals(command.name)) { reload(command); summary = "热更新"; }
        else if ("test".equals(command.name)) { test(command); summary = "测试完成"; }
        else if ("status".equals(command.name)) { status(command); summary = "状态查询"; }
        else if ("build-status".equals(command.name)) { buildStatus(command); summary = "构建状态查询"; }
        else if ("version".equals(command.name)) { printVersion(); summary = "版本查询"; }
        else if ("logs".equals(command.name)) { logs(command); summary = "日志查询"; }
        else if ("stop".equals(command.name)) { stop(command); summary = "停止进程"; }
        else if ("list".equals(command.name)) { list(command); summary = "列出应用"; }
        else if ("tools".equals(command.name)) { tools(command); summary = "工具查询"; }
        else if ("report".equals(command.name)) { report(command); summary = "生成报告"; }
        else throw new IllegalArgumentException("不支持的命令：" + command.name);
      }
      catch (Exception error) {
        success = false;
        summary = "失败: " + error.getMessage();
        throw error;
      }
      finally {
        try {
          recordCommand(command.optionalProject(), command.name, String.join(" ", arguments), success, System.currentTimeMillis() - startTime, summary);
        } catch (Exception ignored) {}
      }
    }
    catch (Exception error) {
      System.out.println(json(mapOf("success", false, "error", error.getMessage())));
      System.exit(1);
    }
  }

  /** 判断环境变量是否为真值（1/true/yes/on）。 */
  static boolean truthyEnv(String name) {
    String value = System.getenv(name);
    if (value == null) return false;
    String normalized = value.trim().toLowerCase();
    return "1".equals(normalized) || "true".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized);
  }

  /** 获取 MCP 端口，优先从系统属性或环境变量读取。 */
  static int mcpPort() {
    String prop = System.getProperty("ij.mcp.server.port");
    if (prop != null && !prop.trim().isEmpty()) {
      try { return Integer.parseInt(prop.trim()); } catch (NumberFormatException ignored) {}
    }
    String env = System.getenv("IJ_MCP_SERVER_PORT");
    if (env != null && !env.trim().isEmpty()) {
      try { return Integer.parseInt(env.trim()); } catch (NumberFormatException ignored) {}
    }
    return DEFAULT_MCP_PORT;
  }

  /**
   * 检查 IDEA MCP 是否可用并连接，仅连接已运行的实例，不启动 IDEA。返回 null 表示不可用。
   *
   * @param project 目标项目路径
   * @return 已连接的 McpClient 实例；若 MCP 端口未就绪则返回 null
   */
  static McpClient connectMcp(Path project) {
    int port = mcpPort();
    if (McpClient.isAvailable(port)) return new McpClient(port);
    return null;
  }

  /**
   * 确保 IDEA 已运行并连接 MCP（仅供 IDEA 模式启动使用）。
   *
   * @param project 目标项目路径
   * @return 已连接的 McpClient 实例；若启动超时或失败则返回 null
   * @throws IOException I/O 异常
   * @throws InterruptedException 中断异常
   */
  static McpClient ensureIdeaRunningAndConnect(Path project) throws IOException, InterruptedException {
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
    McpClient mcp = ensureIdeaRunningAndConnect(project);
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
  private static boolean runControlViaMcp(McpClient mcp, Path project, String configName, String action) {
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
      System.err.println("IDEA MCP 运行控制失败: " + e.getMessage());
      return false;
    }
  }

  /** 将 MCP control_run_configuration 返回转换为 Map 对象。 */
  static Map<String, Object> parseMcpRunResult(String mcpResult, Path project, String configName, String action) {
    String content = unwrapMcpContent(mcpResult);
    boolean ok = content.contains("\"ok\":true") || content.contains("\"ok\": true");
    if (!ok) {
      Map<String, Object> err = new LinkedHashMap<String, Object>();
      err.put("success", false);
      err.put("engine", "idea-mcp");
      err.put("error", "IDEA MCP " + action + " 失败: " + content);
      return err;
    }

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
    return result;
  }

  /** 将 MCP control_run_configuration 返回转换为 simple-ide 标准 JSON。 */
  private static String mcpRunResultToJson(String mcpResult, Path project, String configName, String action) {
    return json(parseMcpRunResult(mcpResult, project, configName, action));
  }

  // ==================== CLI 命令 ====================

  /** 执行编译：获取本地排队锁排队，优先 IDEA MCP，失败则 fallback 到本地进程。 */
  private static void build(Command command) throws Exception {
    Path project = command.project();
    boolean skipLock = command.flag("no-lock") || command.flag("skip-lock");
    List<String> modules = command.values("module");
    int timeoutSec = Integer.parseInt(command.option("timeout", "1800"));
    boolean explicitRebuild = command.flag("rebuild") || command.flag("clean");
    boolean autoRebuild = command.flag("auto-rebuild");
    long lockTimeoutMillis = Long.parseLong(command.option("lock-timeout", "0")) * 1000L;

    // 跟踪器在等锁之前创建：这样 build-status 能看到真实排队阶段，而不是只有编译阶段
    BuildTracker tracker = BuildTracker.start(project, command.option("engine", "jps"), modules, timeoutSec,
      explicitRebuild || autoRebuild);
    tracker.startHeartbeat(System.err);
    try {
      if (skipLock) {
        executeBuild(command, project, tracker);
        return;
      }
      tracker.phase(BuildTracker.PHASE_QUEUED);
      try (BuildLock ignored = BuildLock.acquire(project, BuildLock.defaultLockFile(), lockTimeoutMillis, null)) {
        executeBuild(command, project, tracker);
      }
    }
    finally {
      tracker.close();
    }
  }

  /**
   * 查找 jps-cli.jar 依赖文件路径。
   *
   * @return jps-cli.jar 绝对路径，未找到时返回 null
   */
  static Path findJpsCliJar() {
    try {
      URL location = SimpleIde.class.getProtectionDomain().getCodeSource().getLocation();
      if (location != null) {
        Path codePath = Paths.get(location.toURI());
        Path dir = Files.isDirectory(codePath) ? codePath : codePath.getParent();
        if (dir != null) {
          Path candidate = dir.resolve("jps-cli.jar");
          if (Files.isRegularFile(candidate)) return candidate;
          Path candidateSibling = dir.resolve("../lib/jps-cli.jar").normalize();
          if (Files.isRegularFile(candidateSibling)) return candidateSibling;
        }
      }
    }
    catch (Exception ignored) {}
    Path fallback = Paths.get("/home/lijiang/code/simple-ide/lib/jps-cli.jar");
    if (Files.isRegularFile(fallback)) return fallback;
    return null;
  }

  /**
   * 查找内置 Spring Boot DevTools jar。
   *
   * <p>优先使用 {@code -Dsimple.ide.devtools.jar} 覆盖；否则从工具安装目录的 lib 查找，
   * 最后回退到当前源码仓库的 lib（开发期）。
   *
   * @return DevTools jar 绝对路径；未找到时返回 null
   */
  static Path findDevtoolsJar() {
    String override = System.getProperty("simple.ide.devtools.jar");
    if (override != null && !override.trim().isEmpty()) {
      Path path = Paths.get(override.trim()).toAbsolutePath().normalize();
      if (Files.isRegularFile(path)) return path;
    }
    try {
      URL location = SimpleIde.class.getProtectionDomain().getCodeSource().getLocation();
      if (location != null) {
        Path codePath = Paths.get(location.toURI());
        Path dir = Files.isDirectory(codePath) ? codePath : codePath.getParent();
        if (dir != null) {
          Path candidate = dir.resolve(DEVTOOLS_JAR_NAME);
          if (Files.isRegularFile(candidate)) return candidate;
          Path sibling = dir.resolve("../lib/" + DEVTOOLS_JAR_NAME).normalize();
          if (Files.isRegularFile(sibling)) return sibling;
          Path nested = dir.resolve("lib/" + DEVTOOLS_JAR_NAME).normalize();
          if (Files.isRegularFile(nested)) return nested;
        }
      }
    }
    catch (Exception ignored) {}
    Path fallback = Paths.get("/home/lijiang/code/simple-ide/lib/" + DEVTOOLS_JAR_NAME);
    if (Files.isRegularFile(fallback)) return fallback;
    return null;
  }

  /** 是否启用 simple-ide 内置方法体热替换（环境变量 SIMPLE_IDE_HOTSWAP）。 */
  static boolean hotswapEnabled() {
    return truthyEnv("SIMPLE_IDE_HOTSWAP") || Boolean.parseBoolean(System.getProperty("simple.ide.hotswap", "false"));
  }

  /**
   * 查找 simple-ide 方法体热替换 agent jar。
   *
   * @return agent jar 绝对路径；未找到时返回 null
   */
  static Path findHotswapAgentJar() {
    String override = System.getProperty("simple.ide.hotswap.agent");
    if (override != null && !override.trim().isEmpty()) {
      Path path = Paths.get(override.trim()).toAbsolutePath().normalize();
      if (Files.isRegularFile(path)) return path;
    }
    try {
      URL location = SimpleIde.class.getProtectionDomain().getCodeSource().getLocation();
      if (location != null) {
        Path codePath = Paths.get(location.toURI());
        Path dir = Files.isDirectory(codePath) ? codePath : codePath.getParent();
        if (dir != null) {
          Path candidate = dir.resolve(HOTSWAP_AGENT_JAR_NAME);
          if (Files.isRegularFile(candidate)) return candidate;
          Path sibling = dir.resolve("../lib/" + HOTSWAP_AGENT_JAR_NAME).normalize();
          if (Files.isRegularFile(sibling)) return sibling;
        }
      }
    }
    catch (Exception ignored) {}
    Path fallback = Paths.get("/home/lijiang/code/simple-ide/lib/" + HOTSWAP_AGENT_JAR_NAME);
    if (Files.isRegularFile(fallback)) return fallback;
    Path development = Paths.get("/home/lijiang/code/simple-ide/hotswap-agent/build/libs/" + HOTSWAP_AGENT_JAR_NAME);
    if (Files.isRegularFile(development)) return development;
    return null;
  }

  /** 方法体热替换工作目录。 */
  static Path hotswapDirectory(Path project) {
    return project.resolve(HOTSWAP_DIR);
  }

  /** 方法体热替换运行标记。 */
  static Path hotswapMarker(Path project, String configuration) {
    return hotswapDirectory(project).resolve("enabled-" + configuration + ".json");
  }

  /**
   * 创建工作目录并写入 build 阶段标记，供 reload 判断构建链路已启用。
   *
   * @param project 项目根目录
   * @throws java.io.IOException 创建失败
   */
  static void ensureHotswapWorkspace(Path project) throws java.io.IOException {
    Path directory = hotswapDirectory(project);
    Files.createDirectories(directory.resolve("requests"));
    Files.createDirectories(directory.resolve("classes"));
    Files.createDirectories(directory.resolve("results"));
    if (!Files.exists(directory.resolve("build.json"))) {
      Files.write(directory.resolve("build.json"),
        json(mapOf("enabled", true, "createdAt", System.currentTimeMillis())).getBytes(StandardCharsets.UTF_8));
    }
  }

  /**
   * 判断 classpath 中是否包含文件名含指定片段的条目。
   *
   * @param classpath classpath 字符串
   * @param namePart 文件名片段（小写比较）
   * @return true 表示命中
   */
  static boolean classpathContainsJar(String classpath, String namePart) {
    if (classpath == null || namePart == null) return false;
    for (String entry : classpath.split(File.pathSeparator)) {
      if (entry.trim().isEmpty()) continue;
      try {
        String name = Paths.get(entry).getFileName().toString().toLowerCase();
        if (name.contains(namePart.toLowerCase())) return true;
      }
      catch (Exception ignored) {}
    }
    return false;
  }

  /** 热更新触发文件路径。 */
  static Path hotReloadTrigger(Path project) {
    return project.resolve(HOT_RELOAD_DIR).resolve(HOT_RELOAD_TRIGGER_NAME);
  }

  /** 热更新标记文件路径（按运行配置区分）。 */
  static Path hotReloadMarker(Path project, String configuration) {
    return project.resolve(HOT_RELOAD_DIR).resolve("hot-reload-" + configuration + ".json");
  }

  /**
   * 创建热更新触发文件；已存在时不覆盖，避免 DevTools 启动快照误判。
   *
   * @param project 项目根目录
   * @return 触发文件路径
   * @throws java.io.IOException 创建失败
   */
  static Path ensureHotReloadTrigger(Path project) throws java.io.IOException {
    Path trigger = hotReloadTrigger(project);
    Files.createDirectories(trigger.getParent());
    if (!Files.exists(trigger)) Files.write(trigger, new byte[0]);
    return trigger;
  }

  /**
   * 查找项目依赖 Classpath 缓存文件（历史签名，取第一个命中项）。
   *
   * @param project 项目根目录
   * @return 命中的缓存文件；未找到时返回 null
   * @deprecated 多模块项目请使用 {@link #findClasspathCacheFile(Path, String)} 精确到模块
   */
  @Deprecated
  static File findClasspathCacheFile(Path project) {
    return findClasspathCacheFile(project, null);
  }

  /**
   * 查找项目依赖 Classpath 缓存文件。
   * 优先读取独立的 .jps-deps（jps-cli 当前默认位置），回退到历史位置的 .jps-cache。
   * 之所以分离，是因为 JPS 检测到增量存储损坏时会递归删除整个 .jps-cache 目录。
   *
   * <p>多模块场景必须按模块名精确匹配，禁止「取第一个缓存」造成模块错配；
   * 无法唯一定位时返回 null，由调用方显式报错。
   *
   * @param project 项目根目录
   * @param moduleName 目标模块名；为 null 时仅在能唯一定位时返回
   * @return 命中的缓存文件；未找到或多个候选时返回 null
   */
  static File findClasspathCacheFile(Path project, String moduleName) {
    String[] candidateDirs = {".jps-deps", ".jps-cache"};
    for (String dir : candidateDirs) {
      File[] cacheFiles = project.resolve(dir).toFile()
        .listFiles((d, name) -> name.startsWith("classpath-") && name.endsWith(".cache"));
      if (cacheFiles == null || cacheFiles.length == 0) continue;
      if (moduleName != null && !moduleName.trim().isEmpty()) {
        String expected = "classpath-" + moduleName + ".cache";
        for (File cacheFile : cacheFiles) {
          if (cacheFile.getName().equals(expected)) return cacheFile;
        }
        continue;
      }
      // 未指定模块：仅当候选唯一时才返回，避免静默挑错模块
      if (cacheFiles.length == 1) return cacheFiles[0];
    }
    return null;
  }

  /**
   * 收集当前项目中仍在运行的 Application 配置名。
   *
   * @param project 项目根目录
   * @return 存活的配置名列表
   */
  static List<String> runningConfigurations(Path project) {
    List<String> running = new ArrayList<String>();
    for (Map<String, Object> app : collectAssociatedApps(project)) {
      if (Boolean.TRUE.equals(app.get("alive"))) {
        running.add(String.valueOf(app.get("configuration")));
      }
    }
    return running;
  }

  /** 上一次构建被强制终止（未完整完成）的标记文件路径。 */
  static Path incompleteBuildMarker(Path project) {
    return project.resolve(BuildTracker.BUILDS_DIR).resolve("incomplete.json");
  }

  /**
   * 标记上一次构建未完整完成：下一次构建不能把这份不完整缓存/产物当成有效结果。
   *
   * @param project 项目根目录
   * @param buildId 构建 ID
   * @param errorCode 终止原因错误码
   */
  static void markIncompleteBuild(Path project, String buildId, String errorCode) {
    markIncompleteBuildAt(incompleteBuildMarker(project), buildId, errorCode);
  }

  /**
   * 在指定路径原子写入「未完整构建」标记。
   *
   * @param markerPath 标记文件路径
   * @param buildId 构建 ID
   * @param errorCode 原因码
   */
  static void markIncompleteBuildAt(Path markerPath, String buildId, String errorCode) {
    if (markerPath == null) return;
    try {
      Map<String, Object> marker = new LinkedHashMap<String, Object>();
      marker.put("buildId", buildId);
      marker.put("errorCode", errorCode);
      marker.put("timestamp", System.currentTimeMillis());
      AtomicFiles.write(markerPath, json(marker));
    }
    catch (Exception ignored) {
    }
  }

  /** 清除「构建未完整完成」标记（仅在构建成功后调用）。 */
  static void clearIncompleteBuildMarker(Path project) {
    try {
      Files.deleteIfExists(incompleteBuildMarker(project));
    }
    catch (Exception ignored) {
    }
  }

  /**
   * 仅当标记属于本次构建时才清除，避免把更新的构建所写的标记误删。
   *
   * @param project 项目根目录
   * @param buildId 本次构建 ID
   */
  static void clearIncompleteBuildMarkerIfOwned(Path project, String buildId) {
    Path marker = incompleteBuildMarker(project);
    if (buildId == null || buildId.isEmpty()) {
      clearIncompleteBuildMarker(project);
      return;
    }
    try {
      String content = AtomicFiles.read(marker);
      if (content == null) return;
      Map<String, Object> parsed = JsonParser.tryParseObject(content);
      Object owner = parsed == null ? null : parsed.get("buildId");
      if (owner == null || buildId.equals(String.valueOf(owner))) {
        Files.deleteIfExists(marker);
      }
    }
    catch (Exception ignored) {
    }
  }

  /**
   * 通过内置 JPS CLI 执行快速增量编译。
   *
   * <p>可靠性约束：
   * <ul>
   *   <li>超时从进程启动时刻开始计时，输出并发消费，日志内存有上限；</li>
   *   <li>超时/取消时回收整个子进程树并等待退出；</li>
   *   <li>未正常完成时写入「未完整构建」标记，下一次构建会先做一次全量重建；</li>
   *   <li>显式 rebuild 与 auto-rebuild 语义不同：显式 rebuild 直接清理重建（运行中的应用会阻止清理），
   *       auto-rebuild 先增量，只有命中明确的缓存/历史 class 损坏特征才自动重建一次。</li>
   * </ul>
   *
   * @param project 目标项目根目录
   * @param modules 目标模块（artifactId），可为空
   * @param files 显式文件（JPS 不支持文件粒度，非空时显式拒绝）
   * @param timeoutSec 超时时间（秒）
   * @param explicitRebuild 用户显式要求清理重建
   * @param autoRebuild 允许在识别到缓存故障时自动重建一次
   * @param tracker 可选的构建跟踪器
   * @return 构建状态；若 jps-cli 不可用或非 Maven 项目则返回 null
   */
  private static BuildStatus buildViaJps(Path project, List<String> modules, List<String> files, int timeoutSec,
                                         boolean explicitRebuild, boolean autoRebuild, boolean forceIsolateCache,
                                         boolean noIsolateCache, boolean autoRestartEnabled, boolean allowLiveWrite,
                                         BuildTracker tracker, java.io.PrintStream jsonOut) {
    Path jpsJar = findJpsCliJar();
    if (jpsJar == null) return null;
    Path pomFile = project.resolve("pom.xml");
    if (!Files.isRegularFile(pomFile)) return null;

    if (files != null && !files.isEmpty()) {
      return failJps(project, tracker, jpsJar, timeoutSec, "UNSUPPORTED_FILE_SCOPE", null, null, false, null,
        "JPS 引擎不支持按文件粒度的构建作用域（--file " + files + "）；请改用 --module <artifactId>，"
          + "或使用 --engine idea-mcp。已拒绝执行以避免静默忽略作用域。", 0, null, jsonOut, null);
    }

    // 模块名必须真实存在；未知或歧义时显式报错，同时把 IntelliJ 模块名规范化为 artifactId
    List<String> effectiveModules = new ArrayList<String>();
    if (modules != null && !modules.isEmpty()) {
      ModuleScope scope = resolveModuleScope(project, modules);
      if (scope.error != null) {
        return failJps(project, tracker, jpsJar, timeoutSec, "INVALID_MODULE_SCOPE", null, null, false, null,
          scope.error, 0, null, jsonOut, null);
      }
      effectiveModules.addAll(scope.names);
      if (tracker != null) tracker.put("modules", effectiveModules);
    }

    // 隔离报告：仅在受保护隔离构建时被赋值；上方早退路径引用它时为 null（不会写入隔离字段）
    IsolationReport isolation = null;

    // 1) 残留 JPS 构建检测：父命令被强杀后可能遗留仍在写缓存的 JPS
    List<Long> orphans = detectOrphanJpsBuilds(project, jpsJar);
    if (!orphans.isEmpty()) {
      return failJps(project, tracker, jpsJar, timeoutSec, "ORPHAN_JPS_BUILD_DETECTED", null, null, false,
        "orphan_jps_build_alive",
        "检测到仍在运行的 JPS 构建进程 " + orphans + "（很可能是上一次命令被强制终止后遗留）；"
          + "为避免与遗留进程并发写同一构建缓存，已拒绝本次构建。请确认并结束该进程后重试。", 0, null, jsonOut, isolation);
    }

    boolean clean = explicitRebuild;
    String rebuildReason = explicitRebuild ? "explicit_rebuild" : null;

    // 2) 未完整构建标记：上次构建未确认完成 → 需要一次全量重建（与其他 clean 入口共用同一保护）
    if (!clean && Files.isRegularFile(incompleteBuildMarker(project))) {
      clean = true;
      rebuildReason = "previous_build_incomplete";
      if (tracker != null) tracker.message("检测到上一次构建未完整完成，本次先执行一次全量重建");
    }

    // 3) 存储预检：JPS 内部会在存储打开失败时删除 dataStorageRoot 再全量重建，且无开关可关闭
    JpsCacheValidator.Report cacheReport = JpsCacheValidator.validate(project);
    if (cacheReport.corrupt) {
      if (clean) {
        if (tracker != null) tracker.message("已授权清理：忽略检测到的 JPS 存储损坏（" + cacheReport.describe() + "）");
      }
      else if (autoRebuild) {
        clean = true;
        rebuildReason = "jps_cache_corruption_detected";
        if (tracker != null) {
          tracker.message("检测到 JPS 增量存储损坏（" + cacheReport.describe() + "），本次按授权执行一次全量重建");
        }
      }
      else {
        return failJps(project, tracker, jpsJar, timeoutSec, "JPS_CACHE_CORRUPTED_REBUILD_REQUIRED", null,
          runningConfigurations(project), true, "jps_cache_corruption_detected",
          "检测到 JPS 增量存储损坏（" + cacheReport.describe() + "）。JPS 会以内置逻辑删除并重建该缓存，"
            + "但本次未授权自动重建，已拒绝启动构建以避免未授权的破坏性恢复。"
            + "请先停止运行中的应用并执行 simple-ide build --auto-rebuild（或 --rebuild）后重试。", 0, null, jsonOut, isolation);
      }
    }

    // 4) 唯一清理闸门：所有 clean 入口都必须先确认无运行中应用
    List<String> running = runningConfigurations(project);
    // live reload 直接写共享 target/classes；清理重建会删除运行中应用正在使用的产物，必须拒绝
    if (allowLiveWrite && clean) {
      return failJps(project, tracker, jpsJar, timeoutSec, "LIVE_RELOAD_REQUIRES_RESTART", null, running, true,
        rebuildReason, "本次构建需要清理共享输出（" + rebuildReason + "），不能对运行中应用执行 live reload；"
          + "请先 simple-ide stop，再执行 build/rebuild 后重新 run --hot-reload。", 0, null, jsonOut, isolation);
    }
    // 4') 运行中应用 + 待编译源文件：默认停止应用 → 用共享输出编译 → 成功后按原参数重启；
    //     否则「受保护构建」只把改动写进隔离目录，运行时仍是旧 class（--no-auto-restart 关闭该行为）。
    List<Map<String, Object>> restartPlan = prepareAutoRestart(project, tracker, autoRestartEnabled);
    if (!restartPlan.isEmpty()) {
      running = new ArrayList<String>();
    }
    if (clean && !running.isEmpty()) {
      return failJps(project, tracker, jpsJar, timeoutSec, "REBUILD_BLOCKED_BY_RUNNING_APP", null, running, true, rebuildReason,
        "存在运行中的 Application，禁止清理共享输出目录：" + running + "；请先 simple-ide stop 后再执行重建。", 0, null, jsonOut, isolation);
    }

    // 5) 受保护构建：只要存在运行中应用且未授权清理，默认把「增量缓存 + 编译输出」一起隔离。
    //    隔离缓存：JPS 内部删除存储根目录时只影响副本；
    //    隔离输出：JPS 内部恢复带来的全量重建只写入隔离输出根，绝不删改运行中应用实际使用的
    //    target/classes、target/test-classes 与资源。
    //    隔离构建不发布到共享 target（延后发布语义），调用方通过 JSON 的 outputsPublished/targetOutputsStale 获知。
    boolean isolateBuild = allowLiveWrite ? false : shouldIsolateCache(clean, forceIsolateCache, noIsolateCache, running.size());
    if (allowLiveWrite && !running.isEmpty()) {
      System.err.println("[simple-ide] live reload 构建：直接写共享 target/classes（运行中应用会读取新产物）");
      if (tracker != null) tracker.message("live reload：直接写共享 target/classes，不在隔离目录构建");
    }
    Path isolatedCacheDir = null;
    Path isolatedOutputRoot = null;
    if (isolateBuild) {
      // 使用项目级稳定隔离目录：首次从共享缓存播种，之后复用，保证受保护构建仍然增量且不写共享状态
      isolatedOutputRoot = isolatedBuildRoot(project).resolve("out");
      try {
        isolatedCacheDir = JpsCacheValidator.prepareIsolatedCache(project);
      }
      catch (IOException shadowFailure) {
        return failJps(project, tracker, jpsJar, timeoutSec, "ISOLATED_BUILD_UNAVAILABLE", null, running, true,
          "isolated_build_unavailable",
          "存在运行中的 Application，但无法准备隔离缓存目录（" + shadowFailure.getMessage()
            + "）。已拒绝在共享缓存/共享输出上执行构建，以免未授权的破坏性恢复影响运行中的应用。", 0, null, jsonOut, isolation);
      }
      System.err.println("[simple-ide] 受保护构建：隔离缓存 " + isolatedCacheDir + "，隔离编译输出 "
        + isolatedOutputRoot + "（共享 .jps-cache 与 target 都不会被本进程改动）");
      if (tracker != null) {
        tracker.message("受保护构建：运行中应用存在且未授权清理，编译与缓存均在隔离目录进行，"
          + "共享 target/.jps-cache 不被改动");
        tracker.put("isolatedCache", isolatedCacheDir.toAbsolutePath().toString());
        tracker.put("isolatedOutputRoot", isolatedOutputRoot.toAbsolutePath().toString());
        tracker.put("isolatedBuild", true);
      }
    }

    String buildId = tracker != null ? tracker.buildId() : BuildTracker.newBuildId();
    // 6) 真正启动前可靠建立「未完整构建」标记（隔离构建只写隔离标记，不污染共享状态）
    Path childMarker = isolateBuild
      ? isolatedBuildRoot(project).resolve("incomplete.json")
      : incompleteBuildMarker(project);
    if (!isolateBuild) {
      markIncompleteBuild(project, buildId, "in_progress");
    }

    // 7) 删除探针：放在实际被 JPS 使用的存储目录内，构建后检查是否被整体删除（不依赖 mtime/条目数）
    Path storageDir = isolatedCacheDir != null ? isolatedCacheDir : JpsCacheValidator.projectCacheDir(project);
    boolean probePlaced = !clean && JpsCacheValidator.placeDeletionProbe(storageDir);

    ProcessRunner.Result first;
    try {
      first = runJpsProcess(project, jpsJar, effectiveModules, clean, timeoutSec, tracker, buildId, isolatedCacheDir,
        isolatedOutputRoot, childMarker);
    }
    catch (RuntimeException unexpected) {
      throw unexpected;
    }
    String firstOutput = first.stdout;
    boolean storageDeleted = probePlaced && !JpsCacheValidator.probeSurvived(storageDir);
    JpsCacheValidator.removeProbe(storageDir);
    boolean internalRecovery = firstOutput.contains("[CACHE_REBUILT]")
      || firstOutput.contains("Project rebuild forced")
      || storageDeleted;

    if (isolatedOutputRoot != null) {
      isolation = new IsolationReport();
      isolation.isolated = true;
      isolation.cacheDir = isolatedCacheDir;
      isolation.outputRoot = isolatedOutputRoot;
      parseOutputMap(firstOutput, isolation.classes, isolation.testClasses);
    }

    if (!first.finished) {
      String errorCode = first.cancelled ? "CANCELLED" : "TIMEOUT";
      String message = first.cancelled
        ? "JPS 构建被取消（已终止子进程树，killComplete=" + first.killComplete + "）"
        : "JPS 编译超时 (" + timeoutSec + "s，已终止子进程树，killComplete=" + first.killComplete + ")";
      if (!isolateBuild) markIncompleteBuild(project, buildId, errorCode);
      if (!first.killComplete) {
        errorCode = "PROCESS_TREE_NOT_TERMINATED";
        message = message + "；未能确认整个子进程树已退出（存活 PID=" + first.survivingPids
          + "），下一次构建前请确认没有遗留 JPS 进程。";
      }
      if (isolateBuild) {
        message = message + "；本次为受保护隔离构建，共享 target 与 .jps-cache 未被改动。";
      }
      return failJps(project, tracker, jpsJar, timeoutSec, errorCode, firstOutput, null, false, rebuildReason, message,
        first.durationMs, Integer.valueOf(first.exitCode), jsonOut, isolation);
    }

    if (first.exitCode == 0) {
      if (!isolateBuild && internalRecovery && !clean) {
        // 共享缓存被 JPS 内部未授权删除重建：构建产物正确，但不能静默当作普通成功。
        // 缓存已完成一次完整重建并自洽，故清除标记（不额外强制下一次全量重建），但仍返回失败与证据。
        if (!isolateBuild) clearIncompleteBuildMarkerIfOwned(project, buildId);
        return failJps(project, tracker, jpsJar, timeoutSec, "JPS_INTERNAL_REBUILD_NOT_AUTHORIZED", firstOutput,
          running, true, "jps_internal_rebuild_not_authorized",
          "JPS 在未授权的情况下删除了共享增量缓存并执行了内部重建（删除探针已确认）。"
            + "构建本身完成，但不能作为普通成功接受；如需受控重建请先停止运行中的应用并使用 --auto-rebuild/--rebuild。",
          first.durationMs, Integer.valueOf(0), jsonOut, isolation);
      }
      if (!isolateBuild) clearIncompleteBuildMarkerIfOwned(project, buildId);
      String reason;
      if (isolateBuild && internalRecovery) reason = "isolated_build_cache_recovered";
      else if (isolateBuild) reason = "isolated_build_no_publish";
      else if (internalRecovery) reason = "jps_internal_cache_rebuild";
      else reason = rebuildReason;
      if (!restartPlan.isEmpty()) {
        List<Map<String, Object>> restarted = restartApplications(project, restartPlan);
        if (tracker != null) tracker.put("restartedApplications", restarted);
        System.err.println("[simple-ide] 已按原参数重启应用：" + restarted);
      }
      BuildStatus ok = okJps(project, tracker, jpsJar, firstOutput, first.durationMs, first.exitCode, clean,
        reason, isolateBuild && internalRecovery, false, first.pid, timeoutSec, jsonOut, isolation);
      return ok;
    }

    int exitCode = first.exitCode;
    if (exitCode == 3 || firstOutput.contains("[CACHE_CORRUPT_REBUILD_REQUIRED]")) {
      if (!isolateBuild) markIncompleteBuild(project, buildId, "cache_corruption_rebuild_required");
      BuildStatus blocked = failJps(project, tracker, jpsJar, timeoutSec, "CACHE_CORRUPTED_REBUILD_REQUIRED", firstOutput,
        running, true, "jps_cache_corruption_detected",
        "JPS 检测到增量存储损坏；由于未授权清理，已按策略拒绝破坏性恢复，需要显式重建。",
        first.durationMs, Integer.valueOf(exitCode), jsonOut, isolation);
      return blocked;
    }
    if (exitCode == 4 || firstOutput.contains("[DEPENDENCY_RESOLUTION_FAILED]")) {
      if (!isolateBuild) markIncompleteBuild(project, buildId, "dependency_resolution_failed");
      BuildStatus failed = failJps(project, tracker, jpsJar, timeoutSec, "DEPENDENCY_RESOLUTION_FAILED", firstOutput, null, false,
        "dependency_resolution_failed",
        "依赖解析失败，已拒绝使用残缺依赖继续构建；证据见 .jps-deps/maven-resolve-*.log。",
        first.durationMs, Integer.valueOf(exitCode), jsonOut, isolation);
      return failed;
    }
    if (exitCode == 5 || firstOutput.contains("[BUILD_LOCKED]")) {
      return failJps(project, tracker, jpsJar, timeoutSec, "BUILD_LOCKED_BY_ORPHAN", firstOutput, null, false,
        "jps_build_lock_held",
        "JPS 构建锁被其它构建（可能为遗留进程）持有，已拒绝并发写同一缓存。",
        first.durationMs, Integer.valueOf(exitCode), jsonOut, isolation);
    }

    // 失败：只有「auto-rebuild + 明确的缓存损坏证据 + 不是真实源码错误」才额外重建一次，且仅一次
    boolean realSourceError = JpsOutputParser.hasRealSourceError(firstOutput);
    boolean cacheEvidence = JpsOutputParser.hasCacheCorruptionEvidence(firstOutput) || internalRecovery;
    if (shouldAutoRebuild(autoRebuild, explicitRebuild, cacheEvidence, realSourceError)) {
      List<String> runningNow = runningConfigurations(project);
      if (!runningNow.isEmpty()) {
        return failJps(project, tracker, jpsJar, timeoutSec, "REBUILD_BLOCKED_BY_RUNNING_APP", firstOutput, runningNow, true,
          "cache_corruption_detected",
          "识别到缓存/历史 class 损坏特征，但存在运行中的 Application，禁止清理共享输出目录：" + runningNow
            + "；请先 simple-ide stop 后重新构建。", first.durationMs, Integer.valueOf(exitCode), jsonOut, isolation);
      }
      if (tracker != null) {
        tracker.phase(BuildTracker.PHASE_REBUILD);
        tracker.message("识别到缓存/历史 class 损坏特征，自动执行一次全量重建");
      }
      markIncompleteBuild(project, buildId, "auto_rebuild_in_progress");
      ProcessRunner.Result second = runJpsProcess(project, jpsJar, effectiveModules, true, timeoutSec, tracker, buildId, null, null,
        incompleteBuildMarker(project));
      if (!second.finished) {
        String errorCode = second.cancelled ? "CANCELLED" : "TIMEOUT";
        markIncompleteBuild(project, buildId, errorCode);
        return failJps(project, tracker, jpsJar, timeoutSec, errorCode, second.stdout, null, false, null,
          "自动重建期间编译超时/取消 (" + timeoutSec + "s)", second.durationMs, Integer.valueOf(second.exitCode), jsonOut, isolation);
      }
      if (second.exitCode == 0) {
        clearIncompleteBuildMarkerIfOwned(project, buildId);
        return okJps(project, tracker, jpsJar, second.stdout, second.durationMs, second.exitCode, true,
          "cache_corruption_detected", false, true, second.pid, timeoutSec, jsonOut, isolation);
      }
      markIncompleteBuild(project, buildId, "auto_rebuild_failed");
      return failJps(project, tracker, jpsJar, timeoutSec, "BUILD_FAILED", second.stdout, null, false, null,
        "自动重建后仍然编译失败", second.durationMs, Integer.valueOf(second.exitCode), jsonOut, isolation);
    }

    String errorCode = realSourceError ? "COMPILATION_ERROR" : (cacheEvidence ? "CACHE_SUSPECT_BUILD_FAILED" : "BUILD_FAILED");
    String message = realSourceError
      ? "编译失败：存在源码级错误"
      : (cacheEvidence ? "编译失败且命中缓存可疑特征（未启用 --auto-rebuild，未自动重建）" : "JPS 编译失败，退出码: " + exitCode);
    if (isolateBuild) {
      message = message + "；本次为受保护隔离构建，共享 target 与 .jps-cache 未被改动。";
    }
    if (realSourceError && !isolateBuild) {
      // 源码级编译错误属于「构建已完整跑完、仅源码有问题」：存储是自洽的，不应让下一次构建强制全量重建
      clearIncompleteBuildMarkerIfOwned(project, buildId);
    }
    BuildStatus failed = failJps(project, tracker, jpsJar, timeoutSec, errorCode, firstOutput, null, false, null, message,
      first.durationMs, Integer.valueOf(exitCode), jsonOut, isolation);
    return failed;
  }

  /**
   * 把受保护隔离构建信息并入测试结果 JSON（明确「测试跑在隔离输出上、共享 target 未更新」）。
   *
   * @param jsonText 测试结果 JSON
   * @param preBuildStatus 前置构建状态
   * @return 合并后的 JSON
   */
  static String iterateIsolationFields(String jsonText, BuildStatus preBuildStatus) {
    if (preBuildStatus == null || !preBuildStatus.isolatedBuild) return jsonText;
    Map<String, Object> extra = new LinkedHashMap<String, Object>();
    extra.put("isolatedBuild", true);
    extra.put("outputsPublished", false);
    extra.put("targetOutputsStale", true);
    extra.put("isolatedOutputs", describeIsolatedOutputs(preBuildStatus.isolatedClasses, preBuildStatus.isolatedTestClasses));
    extra.put("notes", "测试使用受保护隔离构建的输出；共享 target/classes 与 target/test-classes 未被改动。");
    return mergeJsonFields(jsonText, extra);
  }

  /**
   * 构造构建状态并同步隔离信息（run/test 需要据此切换到隔离输出）。
   *
   * @param success 是否成功
   * @param jsonResult 结果 JSON
   * @param isolation 隔离信息；可为 null
   * @return 构建状态
   */
  static BuildStatus newStatus(boolean success, String jsonResult, IsolationReport isolation) {
    BuildStatus status = new BuildStatus(success, jsonResult);
    if (isolation != null && isolation.isolated) {
      status.isolatedBuild = true;
      status.isolatedClasses = isolation.classes;
      status.isolatedTestClasses = isolation.testClasses;
    }
    return status;
  }

  /** 受保护隔离构建报告信息。 */
  static final class IsolationReport {
    /** 是否启用隔离构建。 */
    boolean isolated;
    /** 隔离缓存目录。 */
    Path cacheDir;
    /** 隔离输出根目录。 */
    Path outputRoot;
    /** 模块 → 主输出目录。 */
    Map<String, String> classes = new LinkedHashMap<String, String>();
    /** 模块 → 测试输出目录。 */
    Map<String, String> testClasses = new LinkedHashMap<String, String>();
  }

  /**
   * 把隔离构建字段写入结果 JSON（在序列化之前调用，确保 stdout 可见）。
   *
   * @param result 结果 Map
   * @param isolation 隔离信息；为 null 或未隔离时不写入
   */
  static void applyIsolationFields(Map<String, Object> result, IsolationReport isolation) {
    if (result == null || isolation == null || !isolation.isolated) return;
    result.put("isolatedBuild", true);
    result.put("isolatedCache", isolation.cacheDir != null ? isolation.cacheDir.toAbsolutePath().toString() : null);
    result.put("isolatedOutputRoot", isolation.outputRoot != null ? isolation.outputRoot.toAbsolutePath().toString() : null);
    result.put("isolatedOutputs", describeIsolatedOutputs(isolation.classes, isolation.testClasses));
    result.put("outputsPublished", false);
    result.put("targetOutputsStale", true);
    result.put("notes", "受保护隔离构建：编译输出与增量缓存均在隔离目录；共享 target/classes、target/test-classes "
      + "与资源未被本进程删改。要更新共享输出，请先 simple-ide stop 停止应用后执行普通 build（或 --rebuild）。");
  }

  /** 隔离构建根目录（位于 .simple-ide 下，与共享 target/.jps-cache 完全分离）。 */
  static Path isolatedBuildRoot(Path project) {
    return project.resolve(".simple-ide/isolated-build");
  }

  /**
   * 解析 jps-cli 输出的编译输出映射行。
   *
   * <p>格式：{@code [OUTPUT-MAP] <module>=<classes>|<testClasses>}。
   *
   * @param output 构建输出
   * @param classes 输出参数：模块 → 主输出目录
   * @param testClasses 输出参数：模块 → 测试输出目录
   */
  static void parseOutputMap(String output, Map<String, String> classes, Map<String, String> testClasses) {
    if (output == null) return;
    for (String rawLine : output.split("\n")) {
      String line = rawLine.trim();
      int marker = line.indexOf("[OUTPUT-MAP]");
      if (marker < 0) continue;
      String body = line.substring(marker + "[OUTPUT-MAP]".length()).trim();
      int equals = body.indexOf('=');
      if (equals <= 0) continue;
      String module = body.substring(0, equals).trim();
      String paths = body.substring(equals + 1).trim();
      int pipe = paths.indexOf('|');
      String classesPath = pipe >= 0 ? paths.substring(0, pipe).trim() : paths;
      String testClassesPath = pipe >= 0 ? paths.substring(pipe + 1).trim() : null;
      if (!module.isEmpty() && !classesPath.isEmpty()) classes.put(module, classesPath);
      if (!module.isEmpty() && testClassesPath != null && !testClassesPath.isEmpty()) {
        testClasses.put(module, testClassesPath);
      }
    }
  }

  /**
   * 构造隔离输出的可读报告（模块 → {classes, testClasses}）。
   *
   * @param classes 模块 → 主输出目录
   * @param testClasses 模块 → 测试输出目录
   * @return JSON 可序列化的列表
   */
  static List<Map<String, Object>> describeIsolatedOutputs(Map<String, String> classes, Map<String, String> testClasses) {
    List<Map<String, Object>> described = new ArrayList<Map<String, Object>>();
    if (classes == null || classes.isEmpty()) return described;
    for (Map.Entry<String, String> entry : classes.entrySet()) {
      Map<String, Object> item = new LinkedHashMap<String, Object>();
      item.put("module", entry.getKey());
      item.put("classes", entry.getValue());
      String testDir = testClasses == null ? null : testClasses.get(entry.getKey());
      if (testDir != null) item.put("testClasses", testDir);
      described.add(item);
    }
    return described;
  }

  /**
   * 检测仍在运行的、针对同一项目的 JPS 构建进程（父命令被强杀后可能遗留）。
   *
   * <p>这些残留进程会继续写同一构建缓存，因此在启动新构建前必须阻断；
   * 本方法只检测、不自动结束业务或用户进程。
   *
   * @param project 项目根目录
   * @param jpsJar jps-cli jar 路径
   * @return 残留 JPS 进程 PID 列表
   */
  static List<Long> detectOrphanJpsBuilds(Path project, Path jpsJar) {
    List<Long> result = new ArrayList<Long>();
    String needle = project.toAbsolutePath().normalize().toString();
    String jarName = jpsJar != null ? jpsJar.getFileName().toString() : "jps-cli.jar";
    long selfPid = ProcessTree.currentPid();
    for (Long pid : ProcessTree.findByCommandLine(needle)) {
      String cmdline = ProcessTree.commandLineOf(pid);
      if (cmdline == null) continue;
      if (!cmdline.contains(jarName)) continue;
      if (pid == selfPid) continue;
      if (!result.contains(pid)) result.add(pid);
    }

    // 次要信号：项目构建锁仍被持有，说明可能有 JPS 子进程在构建（父命令被强杀后仍可能存活）
    try {
      String content = AtomicFiles.read(project.resolve(".simple-ide/jps-build.lock"));
      if (content != null) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("PID:\\s*(\\d+)").matcher(content);
        if (matcher.find()) {
          long lockPid = Long.parseLong(matcher.group(1));
          if (lockPid > 0 && lockPid != selfPid && isProcessAlive(lockPid) && !result.contains(lockPid)) {
            result.add(lockPid);
          }
        }
      }
    }
    catch (Exception ignored) {
    }
    return result;
  }

  /**
   * 决定是否使用隔离缓存目录执行本次构建。
   *
   * <p>规则：未授权清理（clean=false）且存在运行中应用时默认隔离；`--isolate-cache` 可强制隔离；
   * `--no-isolate-cache` 可关闭该保护。隔离的意义是让 JPS 内部的「删除存储根目录再重建」
   * 只作用于隔离副本，从而不依赖我们对损坏格式的识别能力。
   *
   * @param clean 本次是否为清理重建
   * @param forceIsolate 是否显式强制隔离
   * @param noIsolate 是否显式关闭隔离
   * @param runningConfigurations 运行中应用数量
   * @return 是否使用隔离缓存
   */
  static boolean shouldIsolateCache(boolean clean, boolean forceIsolate, boolean noIsolate, int runningConfigurations) {
    if (noIsolate || clean) return false;
    if (forceIsolate) return true;
    return runningConfigurations > 0;
  }

  /**
   * 决定是否触发受控自动重建。
   *
   * <p>auto-rebuild 与显式 rebuild 语义不同：它只在「非显式重建 + 命中明确缓存损坏特征 + 不是真实源码错误」
   * 时才额外执行一次全量重建，绝不因为源码错误或 Lombok builder 缺失而重建。
   *
   * @param autoRebuild 是否启用 auto-rebuild
   * @param explicitRebuild 是否为显式 rebuild
   * @param cacheEvidence 是否命中缓存损坏特征
   * @param realSourceError 是否存在真实源码级错误
   * @return 是否应触发一次受控自动重建
   */
  static boolean shouldAutoRebuild(boolean autoRebuild, boolean explicitRebuild, boolean cacheEvidence,
                                   boolean realSourceError) {
    return autoRebuild && !explicitRebuild && cacheEvidence && !realSourceError;
  }

  /** 模块作用域解析结果。 */
  static final class ModuleScope {
    /** 规范化后的 Maven artifactId 列表。 */
    final List<String> names = new ArrayList<String>();
    /** 解析失败说明；成功时为 null。 */
    String error;
  }

  /**
   * 把请求的模块名（IntelliJ 模块名或 artifactId）规范化为唯一确定的 Maven artifactId。
   *
   * @param project 项目根目录
   * @param requested 请求的模块名
   * @return 作用域解析结果（失败时 error 非空）
   */
  static ModuleScope resolveModuleScope(Path project, List<String> requested) {
    ModuleScope scope = new ModuleScope();
    if (requested == null || requested.isEmpty()) return scope;
    List<ModuleResolver.Module> resolved;
    try {
      resolved = ModuleResolver.resolve(project);
    }
    catch (IOException e) {
      scope.error = "解析项目模块失败: " + e.getMessage();
      return scope;
    }
    if (resolved.isEmpty()) {
      scope.error = "未解析到任何 Maven 模块（缺少 pom.xml？）";
      return scope;
    }
    List<String> available = new ArrayList<String>();
    for (ModuleResolver.Module module : resolved) available.add(module.name);
    if (available.isEmpty()) {
      scope.error = "未解析到任何 Maven 模块（缺少 pom.xml？）";
      return scope;
    }
    for (String name : requested) {
      try {
        scope.names.add(ModuleResolver.select(resolved, name).name);
      }
      catch (IOException e) {
        scope.error = "--module " + name + " 无法唯一定位: " + e.getMessage();
        return scope;
      }
    }
    return scope;
  }

  /**
   * 启动 JPS 子进程并实时消费输出。
   *
   * <p>生命周期硬化：进程创建后立即登记 PID（构建状态可在运行期间查到存活 PID）；注册取消令牌；
   * 在子进程存续期间安装 JVM 关闭钩子，父 CLI 收到 TERM 时先取消并回收子进程树，
   * 同时把「未完整构建」标记保留下来（只有构建成功才清除）。
   *
   * @param project 项目根目录
   * @param jpsJar jps-cli jar 路径
   * @param modules 目标模块
   * @param clean 是否清理重建
   * @param timeoutSec 超时秒数
   * @param tracker 构建跟踪器（可为 null）
   * @param buildId 本次构建 ID（用于标记所有权与状态登记）
   * @return 进程执行结果
   */
  static ProcessRunner.Result runJpsProcess(Path project, Path jpsJar, List<String> modules, boolean clean,
                                            int timeoutSec, final BuildTracker tracker, final String buildId,
                                            Path isolatedCacheDir, Path isolatedOutputRoot, final Path markerPath) {
    final Path buildLockFile = project.resolve(".simple-ide/jps-build.lock");
    List<String> processCommand = new ArrayList<String>();
    processCommand.add("java");
    processCommand.add("-Xms2g");
    processCommand.add("-Xmx10g");
    processCommand.add("-XX:+UseG1GC");
    processCommand.add("-jar");
    processCommand.add(jpsJar.toAbsolutePath().toString());
    processCommand.add(project.toAbsolutePath().toString());
    if (clean) processCommand.add("--clean");
    if (isolatedCacheDir != null) {
      processCommand.add("--cache-dir");
      processCommand.add(isolatedCacheDir.toAbsolutePath().toString());
    }
    if (isolatedOutputRoot != null) {
      // 关键：把 classes/test-classes 也隔离，JPS 内部恢复（删除存储后全量重建）不会触碰共享 target
      processCommand.add("--output-root");
      processCommand.add(isolatedOutputRoot.toAbsolutePath().toString());
    }
    if (modules != null) {
      for (String module : modules) {
        processCommand.add("--module");
        processCommand.add(module);
      }
    }
    // 未授权清理时禁止 JPS 内部执行破坏性恢复（删缓存重建），改为向上报告需要重建
    processCommand.add("--on-corrupt-cache");
    processCommand.add("fail");
    // 子进程侧构建锁 + 未完整构建标记：用于父命令被强杀后的残留阻断与状态兜底
    processCommand.add("--build-id");
    processCommand.add(buildId != null ? buildId : "unknown");
    processCommand.add("--lock-file");
    processCommand.add(buildLockFile.toAbsolutePath().toString());
    processCommand.add("--incomplete-marker");
    processCommand.add(markerPath.toAbsolutePath().toString());

    if (tracker != null) {
      tracker.appendLog("$ " + String.join(" ", processCommand) + "\n");
      tracker.phase(BuildTracker.PHASE_DEPENDENCY_RESOLVE);
    }

    ProcessRunner.StreamListener listener = new ProcessRunner.StreamListener() {
      @Override
      public void onChunk(String stream, byte[] data, int length) {
        String text = new String(data, 0, length, StandardCharsets.UTF_8);
        // 构建输出走 stderr，保持 stdout 只有稳定机器可读 JSON
        System.err.print(text);
        System.err.flush();
        if (tracker != null) {
          tracker.appendLog(text);
          String phase = JpsOutputParser.inferPhase(text);
          if (phase != null) tracker.phase(phase);
        }
      }
    };

    final ProcessRunner.CancelToken cancelToken = new ProcessRunner.CancelToken();
    final long[] childPid = new long[]{-1};
    final Thread shutdownHook = new Thread(new Runnable() {
      @Override
      public void run() {
        cancelToken.cancel();
        if (childPid[0] > 0) {
          ProcessTree.terminate(null, childPid[0], 2000, 5000);
        }
        if (markerPath != null) {
          markIncompleteBuildAt(markerPath, buildId, "cli_terminated");
        }
        if (tracker != null) {
          try {
            tracker.finish("CANCELLED", null, "CLI_TERMINATED");
          }
          catch (Throwable ignored) {
          }
        }
      }
    }, "simple-ide-build-shutdown");
    boolean hookRegistered = false;
    try {
      Runtime.getRuntime().addShutdownHook(shutdownHook);
      hookRegistered = true;
    }
    catch (IllegalStateException ignored) {
      // JVM 正在关闭，无法注册；此时不做额外承诺
    }

    ProcessRunner.StartListener startListener = new ProcessRunner.StartListener() {
      @Override
      public void onStarted(long pid) {
        childPid[0] = pid;
        if (tracker != null) {
          tracker.pid(pid);
          tracker.put("jpsPid", pid);
          tracker.put("jpsStartedAtIso", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS")
            .format(new java.util.Date()));
        }
      }
    };

    try {
      return ProcessRunner.run(processCommand, project, timeoutSec, true,
        ProcessRunner.DEFAULT_MAX_OUTPUT_BYTES, listener, cancelToken, startListener);
    }
    finally {
      if (hookRegistered) {
        try {
          Runtime.getRuntime().removeShutdownHook(shutdownHook);
        }
        catch (IllegalStateException ignored) {
        }
      }
      if (tracker != null) tracker.put("jpsPid", childPid[0]);
    }
  }

  /**
   * 构造 JPS 构建成功结果 JSON。
   *
   * @param project 项目根目录
   * @param tracker 构建跟踪器
   * @param jpsJar jps-cli jar
   * @param output 构建输出
   * @param durationMs 耗时
   * @param exitCode 退出码
   * @param clean 是否为清理重建
   * @param rebuildReason 重建原因
   * @param cacheRebuilt JPS 是否内部重建过缓存
   * @param autoRebuildPerformed 是否由 auto-rebuild 触发重建
   * @param pid 子进程 PID
   * @param timeoutSec 超时设置
   * @return 构建状态
   */
  private static BuildStatus okJps(Path project, BuildTracker tracker, Path jpsJar, String output, long durationMs,
                                   int exitCode, boolean clean, String rebuildReason, boolean cacheRebuilt,
                                   boolean autoRebuildPerformed, long pid, int timeoutSec, java.io.PrintStream jsonOut,
                                   IsolationReport isolation) {
    long reported = JpsOutputParser.parseReportedDurationMs(output, durationMs);
    List<Map<String, Object>> diagnostics = JpsOutputParser.parseDiagnostics(output);
    Map<String, Object> diagnosticsSummary = new LinkedHashMap<String, Object>();
    diagnostics = dedupeDiagnostics(diagnostics, diagnosticsSummary);
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("success", true);
    result.put("engine", "jps");
    result.put("phase", "COMPLETED");
    result.put("exitCode", exitCode);
    result.put("durationMs", reported);
    result.put("diagnostics", diagnostics);
    result.put("problems", toProblems(diagnostics));
    result.putAll(diagnosticsSummary);
    attachBuildMetadata(result, project, tracker, jpsJar, timeoutSec, pid);
    if (rebuildReason != null) result.put("rebuildReason", rebuildReason);
    if (cacheRebuilt) result.put("cacheRebuilt", true);
    if (autoRebuildPerformed) result.put("autoRebuildPerformed", true);
    if (clean) result.put("rebuilt", true);
    applyIsolationFields(result, isolation);
    attachAutoRestartFields(result, tracker);
    String resultJson = json(result);
    if (jsonOut != null) jsonOut.println(resultJson);
    if (tracker != null) tracker.finish("SUCCEEDED", Integer.valueOf(exitCode), null);
    return newStatus(true, resultJson, isolation);
  }

  /**
   * 构造 JPS 构建失败结果 JSON（统一错误码、阶段与结构化诊断）。
   *
   * @param project 项目根目录
   * @param tracker 构建跟踪器
   * @param jpsJar jps-cli jar
   * @param timeoutSec 超时设置
   * @param errorCode 统一错误码
   * @param output 构建输出
   * @param runningConfigurations 运行中的配置（阻止清理时使用）
   * @param rebuildRequired 是否要求重建
   * @param message 错误说明
   * @param durationMs 耗时
   * @param exitCode 退出码
   * @return 构建状态
   */
  private static BuildStatus failJps(Path project, BuildTracker tracker, Path jpsJar, int timeoutSec, String errorCode,
                                     String output, List<String> runningConfigurations, boolean rebuildRequired,
                                     String rebuildReason, String message, long durationMs, Integer exitCode,
                                     java.io.PrintStream jsonOut, IsolationReport isolation) {
    List<Map<String, Object>> diagnostics = JpsOutputParser.parseDiagnostics(output);
    Map<String, Object> diagnosticsSummary = new LinkedHashMap<String, Object>();
    diagnostics = dedupeDiagnostics(diagnostics, diagnosticsSummary);
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("success", false);
    result.put("engine", "jps");
    result.put("phase", tracker != null ? tracker.snapshot().get("phase") : "COMPLETED");
    result.put("errorCode", errorCode);
    result.put("error", message);
    if (exitCode != null) result.put("exitCode", exitCode.intValue());
    result.put("durationMs", JpsOutputParser.parseReportedDurationMs(output, durationMs));
    result.put("timedOut", "TIMEOUT".equals(errorCode));
    result.put("cancelled", "CANCELLED".equals(errorCode));
    result.put("diagnostics", diagnostics);
    result.put("problems", toProblems(diagnostics));
    result.putAll(diagnosticsSummary);
    if (rebuildRequired) {
      result.put("rebuildRequired", true);
      if (runningConfigurations != null) result.put("runningConfigurations", runningConfigurations);
    }
    if (rebuildReason != null) result.put("rebuildReason", rebuildReason);
    attachBuildMetadata(result, project, tracker, jpsJar, timeoutSec, -1);
    applyIsolationFields(result, isolation);
    attachAutoRestartFields(result, tracker);
    String resultJson = json(result);
    if (jsonOut != null) jsonOut.println(resultJson);
    if (tracker != null) tracker.finish("FAILED", exitCode, errorCode);
    return newStatus(false, resultJson, isolation);
  }

  /** 注入构建可追溯元数据（构建 ID、PID、日志路径、工具与缓存版本）。 */
  private static void attachBuildMetadata(Map<String, Object> result, Path project, BuildTracker tracker,
                                          Path jpsJar, int timeoutSec, long pid) {
    long effectivePid = pid;
    if (tracker != null) {
      result.put("buildId", tracker.buildId());
      result.put("logFile", tracker.logFile().toAbsolutePath().toString());
      result.put("startedAt", tracker.snapshot().get("startedAtIso"));
      if (effectivePid <= 0) {
        Object trackedPid = tracker.snapshot().get("pid");
        if (trackedPid instanceof Number) effectivePid = ((Number) trackedPid).longValue();
      }
    }
    result.put("pid", effectivePid);
    result.put("timeoutSec", timeoutSec);
    result.put("toolVersion", TOOL_VERSION);
    result.put("cacheFormatVersion", CACHE_FORMAT_VERSION);
    result.put("jpsCliJar", jpsJar != null ? jpsJar.toAbsolutePath().toString() : null);
  }

  /** 把结构化诊断转换为兼容旧字段的 problems 列表。 */
  private static List<Map<String, Object>> toProblems(List<Map<String, Object>> diagnostics) {
    List<Map<String, Object>> problems = new ArrayList<Map<String, Object>>();
    for (Map<String, Object> diagnostic : diagnostics) {
      Map<String, Object> problem = new LinkedHashMap<String, Object>();
      problem.put("kind", diagnostic.get("severity"));
      problem.put("message", diagnostic.get("message"));
      if (diagnostic.containsKey("file")) problem.put("file", diagnostic.get("file"));
      if (diagnostic.containsKey("line")) problem.put("line", diagnostic.get("line"));
      if (diagnostic.containsKey("column")) problem.put("column", diagnostic.get("column"));
      problems.add(problem);
    }
    return problems;
  }

  /**
   * 解析项目 Classpath：按目标模块选择精确输出目录与依赖来源。
   *
   * @param project 项目根目录
   * @param moduleHint 目标模块（IntelliJ 模块名或 artifactId）
   * @param includeTestClasses 是否包含测试产物
   * @return Classpath 计划（含告警）
   * @throws IOException 模块歧义或无法解析
   */
  static ClasspathPlan resolveProjectClasspath(Path project, String moduleHint, boolean includeTestClasses) throws IOException {
    return resolveProjectClasspath(project, moduleHint, includeTestClasses, null, null);
  }

  /**
   * 解析项目 Classpath，可指定隔离输出目录覆盖（受保护隔离构建的产物不在共享 target 下）。
   *
   * @param project 项目根目录
   * @param moduleHint 目标模块
   * @param includeTestClasses 是否包含测试产物
   * @param classesOverride 模块 → 主输出目录（隔离构建）；为 null 时使用共享 target
   * @param testClassesOverride 模块 → 测试输出目录（隔离构建）；为 null 时使用共享 target
   * @return Classpath 计划
   * @throws IOException 模块歧义或无法解析
   */
  static ClasspathPlan resolveProjectClasspath(Path project, String moduleHint, boolean includeTestClasses,
                                               Map<String, String> classesOverride,
                                               Map<String, String> testClassesOverride) throws IOException {
    if (classesOverride == null || classesOverride.isEmpty()) {
      return resolveProjectClasspathFromSharedTarget(project, moduleHint, includeTestClasses);
    }
    ClasspathPlan plan = new ClasspathPlan();
    List<ModuleResolver.Module> modules = ModuleResolver.resolve(project);
    ModuleResolver.Module target = modules.isEmpty() ? null : ModuleResolver.select(modules, moduleHint);
    plan.moduleName = target == null ? null : target.name;
    List<ModuleResolver.Module> closure = target == null ? modules : ModuleResolver.closure(modules, target);
    StringBuilder cp = new StringBuilder();
    for (ModuleResolver.Module module : closure) {
      if (includeTestClasses && testClassesOverride != null) {
        String testDir = testClassesOverride.get(module.name);
        if (testDir != null) cp.append(testDir).append(File.pathSeparator);
      }
      String classesDir = classesOverride.get(module.name);
      if (classesDir != null) cp.append(classesDir).append(File.pathSeparator);
    }
    for (ModuleResolver.Module module : closure) {
      File cacheFile = findClasspathCacheFile(project, module.name);
      if (cacheFile == null && closure.size() == 1) cacheFile = findClasspathCacheFile(project);
      if (cacheFile == null) {
        plan.warnings.add("模块 " + module.name + " 未找到依赖 Classpath 缓存，可能缺少第三方依赖");
        continue;
      }
      appendCacheEntries(project, cacheFile, cp, plan.warnings);
    }
    plan.classpath = cp.toString();
    return plan;
  }

  /**
   * 按共享 target 解析项目 Classpath。
   *
   * @param project 项目根目录
   * @param moduleHint 目标模块
   * @param includeTestClasses 是否包含测试产物
   * @return Classpath 计划
   * @throws IOException 模块歧义或无法解析
   */
  private static ClasspathPlan resolveProjectClasspathFromSharedTarget(Path project, String moduleHint,
                                                                       boolean includeTestClasses) throws IOException {
    ClasspathPlan plan = new ClasspathPlan();
    List<ModuleResolver.Module> modules = ModuleResolver.resolve(project);

    StringBuilder cp = new StringBuilder();
    if (modules.isEmpty()) {
      // 非 Maven 项目/无 pom：保留历史回退行为，但显式告警
      cp.append(project.resolve("target/classes").toAbsolutePath()).append(File.pathSeparator);
      if (includeTestClasses) {
        cp.append(project.resolve("target/test-classes").toAbsolutePath()).append(File.pathSeparator);
      }
      File cacheFile = findClasspathCacheFile(project);
      if (cacheFile != null) {
        appendCacheEntries(project, cacheFile, cp, plan.warnings);
      }
      else {
        plan.warnings.add("未找到依赖 Classpath 缓存，仅使用 target/classes");
      }
      plan.classpath = cp.toString();
      return plan;
    }

    ModuleResolver.Module target = ModuleResolver.select(modules, moduleHint);
    plan.moduleName = target.name;
    List<ModuleResolver.Module> closure = ModuleResolver.closure(modules, target);
    for (ModuleResolver.Module module : closure) {
      if (includeTestClasses) {
        cp.append(module.testOutputDir.toAbsolutePath()).append(File.pathSeparator);
      }
      cp.append(module.outputDir.toAbsolutePath()).append(File.pathSeparator);
    }
    for (ModuleResolver.Module module : closure) {
      File cacheFile = findClasspathCacheFile(project, module.name);
      if (cacheFile == null) {
        // 单模块项目允许回退到唯一缓存；多模块缺失具体模块缓存时显式告警
        if (closure.size() == 1) cacheFile = findClasspathCacheFile(project);
        if (cacheFile == null) {
          plan.warnings.add("模块 " + module.name + " 未找到依赖 Classpath 缓存，可能缺少第三方依赖");
          continue;
        }
      }
      appendCacheEntries(project, cacheFile, cp, plan.warnings);
    }
    plan.classpath = cp.toString();
    return plan;
  }

  /** 追加缓存文件中的条目，并记录缺失 Jar 告警（不静默接受残缺缓存）。 */
  private static void appendCacheEntries(Path project, File cacheFile, StringBuilder cp, List<String> warnings) {
    List<String> missing = new ArrayList<String>();
    try {
      List<String> lines = Files.readAllLines(cacheFile.toPath(), StandardCharsets.UTF_8);
      for (String line : lines) {
        line = line.trim();
        // 注释行（如「已确证无外部依赖」标记）不参与 Classpath 与缺失校验
        if (line.isEmpty() || line.startsWith("#")) continue;
        if (!Files.exists(Paths.get(line))) {
          missing.add(line);
          continue;
        }
        cp.append(line).append(File.pathSeparator);
      }
    }
    catch (Exception e) {
      warnings.add("读取依赖 Classpath 缓存失败: " + cacheFile + " (" + e.getMessage() + ")");
    }
    if (!missing.isEmpty()) {
      // 缺失 Jar 不能静默接受：显式告警并提示重建，避免运行期 NoClassDefFoundError 被误当成代码问题
      warnings.add("依赖 Classpath 缓存列出的 " + missing.size() + " 个 Jar 已不存在（缓存不完整），"
        + "请重新执行 simple-ide build 生成完整缓存：" + cacheFile);
    }
  }

  /** Classpath 解析结果。 */
  static final class ClasspathPlan {
    /** 完整 Classpath 字符串。 */
    String classpath = "";
    /** 命中的模块名；非 Maven 项目为 null。 */
    String moduleName;
    /** 告警信息。 */
    final List<String> warnings = new ArrayList<String>();
  }

  /** 执行实际编译逻辑：优先使用 JPS 增量编译引擎，支持降级到 IDEA MCP。 */
  /**
   * 检测到运行中应用时，按需执行「停止应用 → 共享输出编译 → 重启」的前置编排。
   *
   * 只有「存在待编译源文件」时才停应用，避免无改动构建白白中断服务；显式要求隔离
   * （--isolate-cache / SIMPLE_IDE_ISOLATE_CACHE）或 --no-auto-restart 时保持原有保护行为。
   *
   * @param project 项目根目录
   * @param tracker 构建跟踪器，可为 null
   * @param autoRestartEnabled 是否允许自动停用并重启（由 --no-auto-restart 关闭）
   * @return 重启计划；为空表示本次不需要停/重启
   */
  static List<Map<String, Object>> prepareAutoRestart(Path project, BuildTracker tracker, boolean autoRestartEnabled) {
    List<Map<String, Object>> empty = new ArrayList<Map<String, Object>>();
    List<String> running = runningConfigurations(project);
    if (running.isEmpty()) return empty;
    boolean isolateRequested = truthyEnv("SIMPLE_IDE_ISOLATE_CACHE");
    boolean autoRestartDisabled = !autoRestartEnabled || truthyEnv("SIMPLE_IDE_NO_AUTO_RESTART");
    if (isolateRequested || autoRestartDisabled) {
      if (tracker != null) {
        tracker.message("已显式要求隔离构建或禁用自动重启，运行中的应用 " + running + " 保持不变");
        tracker.put("autoRestartSkipped", isolateRequested ? "isolate_requested" : "auto_restart_disabled");
      }
      return empty;
    }

    PendingSources pending = detectPendingSources(project);
    if (!pending.dirty()) {
      if (tracker != null) {
        tracker.message("检测到运行中的应用 " + running + "，但未发现比编译产物更新的源文件"
          + (pending.newest.isEmpty() ? "" : "（最近源文件：" + pending.newest + "）")
          + "；本次不停用应用，沿用原保护策略");
        tracker.put("autoRestartSkipped", "no_pending_sources");
      }
      return empty;
    }

    List<Map<String, Object>> plan = snapshotRestartPlan(project, running);
    if (tracker != null) {
      tracker.message("检测到运行中的应用 " + running + " 与 " + pending.count + " 个待编译源文件"
        + "（最近改动：" + pending.newest + "）：先停止应用 → 用共享输出编译 → 编译成功后按原参数重启；"
        + "编译失败不会自动重启，需要手动 run 恢复");
      tracker.put("autoRestart", true);
      tracker.put("stoppedApplications", running);
      tracker.put("pendingSourceCount", Integer.valueOf(pending.count));
      tracker.put("pendingNewestSource", pending.newest);
    }
    for (String configuration : running) {
      stopApplication(project, configuration);
    }
    return plan;
  }

  /** 诊断条目输出上限：超出部分只保留计数，避免单个文件的过期 API 警告刷爆 JSON。 */
  private static final int DIAGNOSTICS_OUTPUT_LIMIT = 200;

  /**
   * 诊断去重与汇总：同一 (severity, message) 只保留一条并附出现次数。
   *
   * 真实项目里同一文件常有数百条同规则 WARN（如「XXX 已过时」），逐条输出会把 JSON 刷到几百 KB，
   * 调用方只能再写脚本提取；这里改成去重 + 计数，并补充 diagnosticsTotal/diagnosticsSummary。
   *
   * @param diagnostics 原始诊断列表
   * @param summaryOut 汇总输出（写入 diagnosticsTotal/diagnosticsSummary/diagnosticsUnique/可选 diagnosticsTruncated）
   * @return 去重后的诊断列表（最多 DIAGNOSTICS_OUTPUT_LIMIT 条）
   */
  static List<Map<String, Object>> dedupeDiagnostics(List<Map<String, Object>> diagnostics,
                                                    Map<String, Object> summaryOut) {
    Map<String, Map<String, Object>> uniqueByKey = new LinkedHashMap<String, Map<String, Object>>();
    Map<String, Object> severityCount = new LinkedHashMap<String, Object>();
    int total = 0;
    if (diagnostics != null) {
      for (Map<String, Object> item : diagnostics) {
        if (item == null) continue;
        total++;
        String severity = String.valueOf(item.get("severity"));
        Object previousSeverity = severityCount.get(severity);
        severityCount.put(severity, Integer.valueOf(previousSeverity == null ? 1 : ((Number) previousSeverity).intValue() + 1));
        String key = severity + "|" + item.get("message");
        Map<String, Object> existing = uniqueByKey.get(key);
        if (existing == null) {
          Map<String, Object> copy = new LinkedHashMap<String, Object>(item);
          copy.put("count", Integer.valueOf(1));
          uniqueByKey.put(key, copy);
        }
        else {
          existing.put("count", Integer.valueOf(((Number) existing.get("count")).intValue() + 1));
        }
      }
    }
    List<Map<String, Object>> unique = new ArrayList<Map<String, Object>>(uniqueByKey.values());
    if (summaryOut != null) {
      summaryOut.put("diagnosticsTotal", Integer.valueOf(total));
      summaryOut.put("diagnosticsUnique", Integer.valueOf(unique.size()));
      summaryOut.put("diagnosticsSummary", severityCount);
      if (unique.size() > DIAGNOSTICS_OUTPUT_LIMIT) {
        summaryOut.put("diagnosticsTruncated", Integer.valueOf(unique.size() - DIAGNOSTICS_OUTPUT_LIMIT));
        return new ArrayList<Map<String, Object>>(unique.subList(0, DIAGNOSTICS_OUTPUT_LIMIT));
      }
    }
    return unique;
  }

  /**
   * 把「自动停编启」编排结果注入构建 JSON，供调用方感知应用被停止与重启。
   *
   * 存在 stoppedApplications 但没有 restartedApplications 时，标记为「构建失败导致未重启」，
   * 避免调用方误以为应用仍在运行。
   *
   * @param result 构建结果 JSON
   * @param tracker 构建跟踪器，可为 null
   */
  static void attachAutoRestartFields(Map<String, Object> result, BuildTracker tracker) {
    if (tracker == null) return;
    Map<String, Object> snapshot = tracker.snapshot();
    String[] keys = {"autoRestart", "autoRestartSkipped", "stoppedApplications",
      "pendingSourceCount", "pendingNewestSource", "restartedApplications"};
    for (String key : keys) {
      if (snapshot.containsKey(key)) result.put(key, snapshot.get(key));
    }
    if (snapshot.containsKey("stoppedApplications") && !snapshot.containsKey("restartedApplications")) {
      result.put("restartSkippedBecauseBuildFailed", true);
    }
  }

  /** 待编译源文件探测结果。 */
  static final class PendingSources {
    final int count;
    final String newest;
    final boolean compiled;

    PendingSources(int count, String newest, boolean compiled) {
      this.count = count;
      this.newest = newest;
      this.compiled = compiled;
    }

    /** 是否存在需要编译的源文件。 */
    boolean dirty() {
      return count > 0;
    }
  }

  /** 参与「待编译」比较的源文件根（相对项目根）。 */
  private static final String[] PENDING_SOURCE_ROOTS = {
    "src/main/java", "src/main/kotlin", "src/main/resources",
    "src/test/java", "src/test/kotlin", "src/test/resources"};

  /** 参与「待编译」比较的编译输出根（相对项目根，兼容 Maven 与 Gradle 布局）。 */
  private static final String[] PENDING_OUTPUT_ROOTS = {
    "target/classes", "target/test-classes", "target/generated-sources/annotations",
    "build/classes/java/main", "build/classes/java/test", "build/resources/main", "build/resources/test"};

  /**
   * 探测是否存在比编译产物更新的源文件，仅用于构建前提示，不参与保护策略判定。
   *
   * @param project 项目根目录
   * @return 待编译文件数、最近改动文件与是否已存在编译产物
   */
  static PendingSources detectPendingSources(Path project) {
    List<Path> sources = new ArrayList<Path>();
    for (String rel : PENDING_SOURCE_ROOTS) {
      Path path = project.resolve(rel);
      if (Files.isDirectory(path)) sources.add(path);
    }
    long newestOutput = 0L;
    boolean compiled = false;
    for (String rel : PENDING_OUTPUT_ROOTS) {
      Path path = project.resolve(rel);
      if (!Files.isDirectory(path)) continue;
      compiled = true;
      long mtime = newestModifiedMillis(path);
      if (mtime > newestOutput) newestOutput = mtime;
    }

    int count = 0;
    String newest = "";
    long newestMtime = 0L;
    for (Path root : sources) {
      try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
        java.util.Iterator<Path> iterator = walk.iterator();
        while (iterator.hasNext()) {
          Path file = iterator.next();
          if (!Files.isRegularFile(file)) continue;
          long mtime = Files.getLastModifiedTime(file).toMillis();
          if (mtime > newestMtime) {
            newestMtime = mtime;
            newest = project.relativize(file).toString();
          }
          if (!compiled || mtime > newestOutput) count++;
        }
      }
      catch (Exception ignored) {
      }
    }
    return new PendingSources(count, newest, compiled);
  }

  /** 递归取目录下最新的文件修改时间（毫秒）；无文件或读取失败时返回 0。 */
  static long newestModifiedMillis(Path root) {
    long newest = 0L;
    try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
      java.util.Iterator<Path> iterator = walk.iterator();
      while (iterator.hasNext()) {
        Path file = iterator.next();
        if (!Files.isRegularFile(file)) continue;
        long mtime = Files.getLastModifiedTime(file).toMillis();
        if (mtime > newest) newest = mtime;
      }
    }
    catch (Exception ignored) {
    }
    return newest;
  }

  /**
   * 快照运行中应用的原始启动参数，供构建后按原参数重启。
   *
   * @param project 项目根目录
   * @param configurations 运行中的配置名
   * @return 每项包含 configuration / port / debugPort / java
   */
  static List<Map<String, Object>> snapshotRestartPlan(Path project, List<String> configurations) {
    List<Map<String, Object>> plan = new ArrayList<Map<String, Object>>();
    for (String configuration : configurations) {
      Map<String, Object> item = new LinkedHashMap<String, Object>();
      item.put("configuration", configuration);
      int port = -1;
      int debugPort = -1;
      String java = "";
      try {
        RunState state = RunState.read(runState(project, configuration));
        if (state != null) {
          port = state.port;
          debugPort = state.debugPort;
          if (state.command != null && !state.command.isEmpty()) {
            String first = state.command.get(0);
            if (first != null && (first.endsWith("java") || first.endsWith("java.exe"))) java = first;
          }
        }
      }
      catch (Exception ignored) {
      }
      item.put("port", Integer.valueOf(port));
      item.put("debugPort", Integer.valueOf(debugPort));
      item.put("java", java);
      plan.add(item);
    }
    return plan;
  }

  /**
   * 由重启计划项构造重启命令行：用当前 simple-ide 的 classpath 再次执行 run，
   * 跳过前置编译（本次构建已完成），避免与其它运行中应用互相拦截。
   *
   * @param project 项目根目录
   * @param item 重启计划项
   * @return 命令参数列表
   */
  static List<String> restartCommand(Path project, Map<String, Object> item) {
    String configuration = String.valueOf(item.get("configuration"));
    String java = item.get("java") == null ? "" : String.valueOf(item.get("java"));
    List<String> argv = new ArrayList<String>();
    argv.add(java.isEmpty() || "null".equals(java)
      ? Paths.get(System.getProperty("java.home"), "bin", "java").toString() : java);
    argv.add("-cp");
    argv.add(System.getProperty("java.class.path"));
    argv.add(SimpleIde.class.getName());
    argv.add("run");
    argv.add("--project");
    argv.add(project.toAbsolutePath().toString());
    argv.add("--configuration");
    argv.add(configuration);
    int port = item.get("port") instanceof Number ? ((Number) item.get("port")).intValue() : -1;
    int debugPort = item.get("debugPort") instanceof Number ? ((Number) item.get("debugPort")).intValue() : -1;
    if (port > 0) {
      argv.add("--port");
      argv.add(String.valueOf(port));
    }
    if (debugPort > 0) {
      argv.add("--debug-port");
      argv.add(String.valueOf(debugPort));
    }
    argv.add("--engine");
    argv.add("local");
    argv.add("--skip-build");
    return argv;
  }

  /**
   * 按原参数逐个重启应用（独立子进程执行 run，避免 run 内部的 System.exit 终止本次构建进程）。
   *
   * @param project 项目根目录
   * @param plan 重启计划
   * @return 每个应用的重启结果（configuration / success / exitCode）
   */
  static List<Map<String, Object>> restartApplications(Path project, List<Map<String, Object>> plan) {
    List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
    for (Map<String, Object> item : plan) {
      Map<String, Object> result = new LinkedHashMap<String, Object>();
      result.put("configuration", String.valueOf(item.get("configuration")));
      try {
        ProcessRunner.Result run = ProcessRunner.run(restartCommand(project, item), project, 900, true, 64 * 1024, null, null);
        result.put("exitCode", Integer.valueOf(run.exitCode));
        result.put("success", Boolean.valueOf(run.exitCode == 0));
        if (run.exitCode != 0) {
          result.put("error", run.stdout == null ? "" : run.stdout.trim());
        }
      }
      catch (Exception e) {
        result.put("success", Boolean.FALSE);
        result.put("error", e.getMessage());
      }
      results.add(result);
    }
    return results;
  }

  private static void executeBuild(Command command, Path project, BuildTracker tracker) throws Exception {
    List<String> modules = command.values("module");
    List<String> files = command.values("file");
    int timeoutSec = Integer.parseInt(command.option("timeout", "1800"));
    boolean explicitRebuild = command.flag("rebuild") || command.flag("clean");
    boolean autoRebuild = command.flag("auto-rebuild");
    boolean forceIsolateCache = command.flag("isolate-cache") || truthyEnv("SIMPLE_IDE_ISOLATE_CACHE");
    boolean noIsolateCache = command.flag("no-isolate-cache") || truthyEnv("SIMPLE_IDE_NO_ISOLATE_CACHE");
    String requestedEngine = command.option("engine", "jps");

    BuildStatus status = null;
    if ("jps".equalsIgnoreCase(requestedEngine)) {
      status = buildViaJps(project, modules, files, timeoutSec, explicitRebuild, autoRebuild, forceIsolateCache, noIsolateCache,
        !command.flag("no-auto-restart"), false, tracker, System.out);
      if (status == null && command.option("engine", null) == null) {
        status = buildViaMcp(project, modules, files, timeoutSec, explicitRebuild || autoRebuild);
      }
    }
    else {
      status = buildViaMcp(project, modules, files, timeoutSec, explicitRebuild || autoRebuild);
    }

    if (status == null) {
      tracker.finish("FAILED", null, "NO_ENGINE");
      throw new IllegalStateException("无可用的编译引擎（JPS 或 IDEA MCP 均未就绪）");
    }
    if (!status.success) {
      // 编译失败必须以非零退出码结束，否则调用方仅凭退出码会把失败误判为成功
      System.exit(1);
    }
    if (hotswapEnabled()) {
      if (findHotswapAgentJar() == null) {
        throw new IllegalStateException("SIMPLE_IDE_HOTSWAP 已启用，但未找到 " + HOTSWAP_AGENT_JAR_NAME
          + "；请确认 lib 目录包含该 jar");
      }
      ensureHotswapWorkspace(project);
    }
  }

  /** 启动 Application：默认纯运行模式（不开启 JDWP 调试代理，对齐 IDEA Run 极致性能）；指定 debug 命令或 --debug 时开启远程调试；支持 --random-port / --port 动态覆盖。 */
  private static void run(Command command) throws Exception {
    Path project = command.project();
    String configName = command.required("configuration");
    int port = configuredPort(command);
    String engine = command.option("engine", "local");
    boolean hotReload = command.flag("hot-reload");
    boolean hotswap = hotswapEnabled();

    if (hotswap && hotReload) {
      throw new IllegalArgumentException("SIMPLE_IDE_HOTSWAP 与 --hot-reload 不能同时启用：前者是方法体原地替换，后者是 DevTools 全量 context 重启");
    }
    if (hotswap && "idea-mcp".equalsIgnoreCase(engine)) {
      throw new IllegalArgumentException("SIMPLE_IDE_HOTSWAP 仅支持 --engine local（agent 必须注入本地进程）");
    }
    if ("idea-mcp".equalsIgnoreCase(engine)) {
      if (runViaMcp(project, configName, port, command)) return;
    }

    // 先读取运行配置：模块名决定构建作用域与 Classpath 目标，避免多模块项目错配
    ApplicationConfiguration configuration = ApplicationConfiguration.load(project, configName);
    Path stateFile = runState(project, configuration.name);

    // 启动前先检查同配置是否已在运行：避免隐式前置构建在旧实例仍在运行时清理/覆盖共享输出
    RunState preExisting = RunState.read(stateFile);
    if (preExisting != null && isProcessAlive(preExisting.pid)) {
      throw new IllegalStateException("Application 已在运行，PID=" + preExisting.pid);
    }
    // 本项目已有其它运行中应用时，前置构建只能走隔离输出（不会发布），因此新进程会拿到陈旧 classpath；
    // 这种情况在启动 JPS 之前就拒绝，避免「隔离成功被当成 target 已更新」。
    List<String> runningBeforeRun = runningConfigurations(project);
    if (!runningBeforeRun.isEmpty()) {
      Map<String, Object> blocked = new LinkedHashMap<String, Object>();
      blocked.put("success", false);
      blocked.put("errorCode", "RUN_BLOCKED_BY_RUNNING_APP");
      blocked.put("phase", "PRE_BUILD");
      blocked.put("rebuildRequired", true);
      blocked.put("runningConfigurations", runningBeforeRun);
      blocked.put("rebuildReason", "outputs_not_publishable_while_app_running");
      blocked.put("error", "本项目已有运行中的应用 " + runningBeforeRun
        + "：受保护构建只写到隔离输出、不会发布到共享 target，直接启动会使用陈旧 classpath。"
        + "请先 simple-ide stop 停止这些应用，再执行 run（或先执行普通 build 更新共享输出）。");
      System.out.println(json(blocked));
      System.exit(1);
      return;
    }

    // 前置步骤：增量编译校验（确保最新 class 产物；按运行配置的模块精确限定作用域）
    if (!command.flag("skip-build")) {
      int timeoutSec = Integer.parseInt(command.option("timeout", "300"));
      boolean skipLock = command.flag("no-lock") || command.flag("skip-lock");
      List<String> buildModules = new ArrayList<String>();
      if (configuration.module != null && !configuration.module.trim().isEmpty()) {
        buildModules.add(configuration.module);
      }
      BuildStatus buildStatus;
      if (skipLock) {
        buildStatus = buildViaJps(project, buildModules, null, timeoutSec, false, false, false, false, false, false, null, null);
      } else {
        try (BuildLock ignored = BuildLock.acquire(project)) {
          buildStatus = buildViaJps(project, buildModules, null, timeoutSec, false, false, false, false, false, false, null, null);
        }
      }
      if (buildStatus != null && !buildStatus.success) {
        System.err.println("[ERROR] 启动前置增量编译失败");
        System.exit(1);
      }
      if (buildStatus != null && buildStatus.isolatedBuild) {
        Map<String, Object> blocked = new LinkedHashMap<String, Object>();
        blocked.put("success", false);
        blocked.put("errorCode", "RUN_REQUIRES_PUBLISHED_OUTPUTS");
        blocked.put("phase", "PRE_BUILD");
        blocked.put("rebuildRequired", true);
        blocked.put("rebuildReason", "isolated_build_not_published");
        blocked.put("runningConfigurations", runningConfigurations(project));
        blocked.put("error", "前置构建为受保护隔离构建（未发布到共享 target），直接启动会使用陈旧 classpath；"
          + "请先停止运行中的应用再执行 run。");
        System.out.println(json(blocked));
        System.exit(1);
        return;
      }
    }

    List<String> vmParams = split(configuration.vmParameters);
    // 追加调用方显式传入的 JVM 参数（--vm-arg，可重复）：供启动期 profiling（-agentpath）、临时系统属性等使用；
    // 参数原样透传不拆分，避免路径/等号/逗号被误解析，最终插在 -cp 之前。
    List<String> extraVmArgs = command.values("vm-arg");
    if (extraVmArgs != null && !extraVmArgs.isEmpty()) {
      vmParams.addAll(extraVmArgs);
    }
    Path hotswapAgent = null;
    if (hotswap) {
      hotswapAgent = findHotswapAgentJar();
      if (hotswapAgent == null) {
        throw new IllegalStateException("未找到 " + HOTSWAP_AGENT_JAR_NAME
          + "；请确认 lib 目录包含该 jar，或通过 -Dsimple.ide.hotswap.agent 指定路径");
      }
      ensureHotswapWorkspace(project);
      vmParams.add("-javaagent:" + hotswapAgent.toAbsolutePath() + "=dir="
        + hotswapDirectory(project).toAbsolutePath());
    }
    int debugPort = resolveDebugPort(command, vmParams);
    Path runDirectory = project.resolve(".simple-ide/runs");
    Files.createDirectories(runDirectory);

    // 按运行配置的模块解析精确 Classpath（模块输出目录 + 模块依赖缓存）；缓存缺 Jar 时显式告警
    ClasspathPlan plan = resolveProjectClasspath(project, configuration.module, false);
    for (String warning : plan.warnings) {
      System.err.println("[WARN] " + warning);
    }
    String classpath = plan.classpath;

    Path devtoolsJar = null;
    if (hotReload) {
      if (!classpathContainsJar(classpath, "spring-boot-")) {
        throw new IllegalStateException("--hot-reload 只支持 Spring Boot 应用：classpath 未发现 spring-boot-*.jar");
      }
      if (classpathContainsJar(classpath, "jrebel") || classpathContainsJar(classpath, "hotswap-agent")
        || classpathContainsJar(classpath, "hotswapagent")) {
        throw new IllegalStateException("检测到 JRebel/HotSwapAgent：DevTools 会自动禁用重启，不能同时启用 --hot-reload");
      }
      if (!classpathContainsJar(classpath, "spring-boot-devtools")) {
        devtoolsJar = findDevtoolsJar();
        if (devtoolsJar == null) {
          throw new IllegalStateException("未找到内置 " + DEVTOOLS_JAR_NAME + "；请确认 lib 目录包含该 jar，"
            + "或通过 -Dsimple.ide.devtools.jar 指定路径");
        }
        classpath = classpath + File.pathSeparator + devtoolsJar.toAbsolutePath();
      }
      List<String> hotReloadIncludes = command.values("hot-reload-include");
      if (!hotReloadIncludes.isEmpty()) {
        Path settingsDir = project.resolve(HOT_RELOAD_DIR).resolve("spring-devtools");
        Path metaInf = settingsDir.resolve("META-INF");
        Files.createDirectories(metaInf);
        StringBuilder properties = new StringBuilder();
        for (int index = 0; index < hotReloadIncludes.size(); index++) {
          properties.append("restart.include.").append(index).append("=")
            .append(hotReloadIncludes.get(index)).append("\n");
        }
        Files.write(metaInf.resolve("spring-devtools.properties"), properties.toString().getBytes(StandardCharsets.UTF_8));
        classpath = classpath + File.pathSeparator + settingsDir.toAbsolutePath();
      }
      ensureHotReloadTrigger(project);
      vmParams.add("-Dspring.devtools.restart.enabled=true");
      vmParams.add("-Dspring.devtools.restart.trigger-file=" + HOT_RELOAD_TRIGGER_NAME);
      vmParams.add("-Dspring.devtools.restart.additional-paths="
        + project.resolve(HOT_RELOAD_DIR).toAbsolutePath());
    }

    List<String> processCommand = buildJavaCommandLine(
        command.option("java", "java"),
        vmParams,
        debugPort,
        classpath,
        configuration.mainClass,
        port,
        split(configuration.programParameters)
    );

    Path log = runDirectory.resolve(configuration.name + ".log");
    Process process = new ProcessBuilder(processCommand)
      .directory(configuration.workingDirectory(project).toFile())
      .redirectErrorStream(true)
      .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
      .start();
    RunState state = new RunState(getPid(process), log, port, debugPort, project.toString(), processCommand);
    state.write(stateFile);
    Path marker = hotReloadMarker(project, configuration.name);
    if (hotReload) {
      Map<String, Object> markerData = mapOf(
        "configuration", configuration.name,
        "module", configuration.module,
        "devtoolsJar", devtoolsJar != null ? devtoolsJar.toAbsolutePath().toString() : "project-classpath",
        "trigger", hotReloadTrigger(project).toAbsolutePath().toString(),
        "startedAt", System.currentTimeMillis());
      Files.createDirectories(marker.getParent());
      Files.write(marker, json(markerData).getBytes(StandardCharsets.UTF_8));
    }
    else {
      Files.deleteIfExists(marker);
    }
    Path hotswapMarker = hotswapMarker(project, configuration.name);
    if (hotswap) {
      Map<String, Object> markerData = mapOf(
        "configuration", configuration.name,
        "module", configuration.module,
        "agent", hotswapAgent != null ? hotswapAgent.toAbsolutePath().toString() : "",
        "directory", hotswapDirectory(project).toAbsolutePath().toString(),
        "startedAt", System.currentTimeMillis());
      Files.createDirectories(hotswapMarker.getParent());
      Files.write(hotswapMarker, json(markerData).getBytes(StandardCharsets.UTF_8));
    }
    else {
      Files.deleteIfExists(hotswapMarker);
    }
    Map<String, Object> result = state.toMap(port > 0 ? "STARTING" : "RUNNING");
    if (hotReload) {
      result.put("hotReload", true);
      result.put("trigger", hotReloadTrigger(project).toAbsolutePath().toString());
      if (devtoolsJar != null) result.put("devtoolsJar", devtoolsJar.toAbsolutePath().toString());
    }
    if (hotswap) {
      result.put("hotswap", true);
      result.put("hotswapAgent", hotswapAgent.toAbsolutePath().toString());
      result.put("hotswapDir", hotswapDirectory(project).toAbsolutePath().toString());
    }
    System.out.println(json(result));
  }

  /**
   * 对运行中的 Spring Boot 应用执行一次 live reload：增量编译到共享 target/classes，
   * 再更新 DevTools trigger 文件触发上下文重启。
   *
   * <p>前提：应用由 {@code run --hot-reload} 启动，且 classpath 上有 DevTools。
   *
   * @param command 命令参数
   * @throws Exception 读取配置、构建或写 trigger 失败
   */
  private static void reload(Command command) throws Exception {
    Path project = command.project();
    String configName = command.option("configuration", "Application");
    if (hotswapEnabled()) {
      hotswapReload(command);
      return;
    }
    ApplicationConfiguration configuration = ApplicationConfiguration.load(project, configName);
    RunState state = RunState.read(runState(project, configName));
    if (state == null || !isProcessAlive(state.pid)) {
      System.out.println(json(mapOf("success", false, "configuration", configName,
        "errorCode", "NOT_RUNNING", "error", "Application 未运行：" + configName)));
      System.exit(1);
      return;
    }
    Path marker = hotReloadMarker(project, configName);
    if (!Files.isRegularFile(marker)) {
      System.out.println(json(mapOf("success", false, "configuration", configName,
        "errorCode", "HOT_RELOAD_NOT_ENABLED",
        "error", "该 Application 不是通过 run --hot-reload 启动的；请停止后重新用 --hot-reload 启动")));
      System.exit(1);
      return;
    }
    Path trigger = ensureHotReloadTrigger(project);
    List<String> modules = new ArrayList<String>();
    if (configuration.module != null && !configuration.module.trim().isEmpty()) {
      modules.add(configuration.module);
    }
    int timeoutSec = Integer.parseInt(command.option("timeout", "600"));
    BuildTracker tracker = BuildTracker.start(project, "jps", modules, timeoutSec, false);
    tracker.startHeartbeat(System.err);
    BuildStatus status;
    try {
      try (BuildLock ignored = BuildLock.acquire(project)) {
        status = buildViaJps(project, modules, null, timeoutSec, false, false, false, false, false, true, tracker, null);
      }
    }
    finally {
      tracker.close();
    }
    if (status == null) {
      System.out.println(json(mapOf("success", false, "configuration", configName,
        "errorCode", "NO_ENGINE", "error", "JPS 构建引擎不可用")));
      System.exit(1);
      return;
    }
    if (!status.success) {
      Map<String, Object> build = JsonParser.tryParseObject(status.jsonResult);
      Map<String, Object> failure = mapOf("success", false, "configuration", configName,
        "errorCode", "BUILD_FAILED", "build", build != null ? build : status.jsonResult,
        "buildId", tracker.buildId(), "logFile", tracker.logFile().toAbsolutePath().toString());
      System.out.println(json(failure));
      System.exit(1);
      return;
    }
    Files.write(trigger, ("reload " + System.currentTimeMillis() + "\n").getBytes(StandardCharsets.UTF_8));
    System.out.println(json(mapOf(
      "success", true,
      "configuration", configName,
      "compiled", true,
      "buildId", tracker.buildId(),
      "logFile", tracker.logFile().toAbsolutePath().toString(),
      "trigger", trigger.toAbsolutePath().toString(),
      "restartExpected", true,
      "note", "DevTools 轮询触发文件（默认 1s + 400ms quiet period）后重启 context；可用 logs 查看结果")));
  }

  /**
   * 方法体热替换：JPS 增量编译到共享 target/classes，把变更 class 交给 agent 执行
   * {@code redefineClasses}，不重启 Spring context。
   *
   * @param command 命令参数
   * @throws Exception 读取配置、构建或与 agent 通信失败
   */
  private static void hotswapReload(Command command) throws Exception {
    Path project = command.project();
    String configName = command.option("configuration", "Application");
    ApplicationConfiguration configuration = ApplicationConfiguration.load(project, configName);
    RunState state = RunState.read(runState(project, configName));
    if (state == null || !isProcessAlive(state.pid)) {
      System.out.println(json(mapOf("success", false, "configuration", configName,
        "errorCode", "NOT_RUNNING", "error", "Application 未运行：" + configName)));
      System.exit(1);
      return;
    }
    Path marker = hotswapMarker(project, configName);
    if (!Files.isRegularFile(marker)) {
      System.out.println(json(mapOf("success", false, "configuration", configName,
        "errorCode", "HOTSWAP_NOT_ENABLED",
        "error", "应用不是通过 SIMPLE_IDE_HOTSWAP 启动的；请停止后设置环境变量重新 run")));
      System.exit(1);
      return;
    }
    ensureHotswapWorkspace(project);
    List<String> modules = new ArrayList<String>();
    if (configuration.module != null && !configuration.module.trim().isEmpty()) {
      modules.add(configuration.module);
    }
    int buildTimeout = Integer.parseInt(command.option("build-timeout", "600"));
    int waitTimeout = Integer.parseInt(command.option("timeout", "30"));
    long reloadStarted = System.currentTimeMillis();
    BuildTracker tracker = BuildTracker.start(project, "jps", modules, buildTimeout, false);
    tracker.startHeartbeat(System.err);
    BuildStatus status;
    try {
      try (BuildLock ignored = BuildLock.acquire(project)) {
        status = buildViaJps(project, modules, null, buildTimeout, false, false, false, false, false, true, tracker, null);
      }
    }
    finally {
      tracker.close();
    }
    if (status == null || !status.success) {
      Map<String, Object> build = status == null ? null : JsonParser.tryParseObject(status.jsonResult);
      System.out.println(json(mapOf("success", false, "configuration", configName,
        "errorCode", "BUILD_FAILED", "build", build != null ? build : status == null ? "NO_ENGINE" : status.jsonResult,
        "buildId", tracker.buildId(), "logFile", tracker.logFile().toAbsolutePath().toString())));
      System.exit(1);
      return;
    }

    Path classesRoot = hotswapDirectory(project).resolve("classes");
    List<String> relativePaths = new ArrayList<String>();
    List<ModuleResolver.Module> resolved = ModuleResolver.resolve(project);
    ModuleResolver.Module target = resolved.isEmpty() ? null : ModuleResolver.select(resolved, configuration.module);
    List<ModuleResolver.Module> closure = target == null ? resolved : ModuleResolver.closure(resolved, target);
    for (ModuleResolver.Module module : closure) {
      Path output = module.outputDir;
      if (!Files.isDirectory(output)) {
        continue;
      }
      try (java.util.stream.Stream<Path> stream = Files.walk(output)) {
        java.util.Iterator<Path> iterator = stream.iterator();
        while (iterator.hasNext()) {
          Path file = iterator.next();
          String name = file.getFileName().toString();
          if (!Files.isRegularFile(file) || !name.endsWith(".class")) {
            continue;
          }
          if (Files.getLastModifiedTime(file).toMillis() < reloadStarted - 2000L) {
            continue;
          }
          String relative = output.relativize(file).toString().replace(File.separatorChar, '/');
          Path destination = classesRoot.resolve(relative);
          Files.createDirectories(destination.getParent());
          Files.copy(file, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
          relativePaths.add(relative);
        }
      }
    }
    if (relativePaths.isEmpty()) {
      System.out.println(json(mapOf("success", true, "configuration", configName,
        "compiled", true, "redefined", 0, "note", "没有发现需要热替换的 class")));
      return;
    }
    String id = "reload-" + System.currentTimeMillis();
    Path request = hotswapDirectory(project).resolve("requests").resolve(id + ".req");
    Files.write(request, relativePaths, StandardCharsets.UTF_8);
    Path result = hotswapDirectory(project).resolve("results").resolve(id + ".txt");
    long deadline = System.currentTimeMillis() + waitTimeout * 1000L;
    while (System.currentTimeMillis() < deadline) {
      if (Files.isRegularFile(result)) {
        List<String> lines = Files.readAllLines(result, StandardCharsets.UTF_8);
        String statusLine = lines.isEmpty() ? "" : lines.get(0).trim();
        if ("OK".equals(statusLine)) {
          Map<String, Object> response = mapOf("success", true, "configuration", configName,
            "compiled", true, "redefined", parseCount(lines, "redefined="),
            "skipped", parseCount(lines, "skipped="), "classes", relativePaths,
            "agentLog", tail(hotswapDirectory(project).resolve("agent.log"), 20));
          Files.deleteIfExists(result);
          System.out.println(json(response));
          return;
        }
        String code = lines.size() > 1 ? lines.get(1).trim() : "REDEFINE_FAILED";
        String message = lines.size() > 2 ? lines.get(2).trim() : "热替换失败";
        Map<String, Object> failure = mapOf("success", false, "configuration", configName,
          "errorCode", code, "error", message, "classes", relativePaths,
          "agentLog", tail(hotswapDirectory(project).resolve("agent.log"), 20));
        Files.deleteIfExists(result);
        System.out.println(json(failure));
        System.exit(1);
        return;
      }
      if (!isProcessAlive(state.pid)) {
        System.out.println(json(mapOf("success", false, "configuration", configName,
          "errorCode", "APP_EXITED", "error", "应用进程已退出，无法完成热替换")));
        System.exit(1);
        return;
      }
      Thread.sleep(200L);
    }
    System.out.println(json(mapOf("success", false, "configuration", configName,
      "errorCode", "HOTSWAP_AGENT_TIMEOUT",
      "error", "等待 hotswap agent 结果超时（" + waitTimeout + "s）",
      "classes", relativePaths,
      "agentLog", tail(hotswapDirectory(project).resolve("agent.log"), 20))));
    System.exit(1);
  }

  /** 读取 agent 结果中的计数行。 */
  private static int parseCount(List<String> lines, String prefix) {
    for (String line : lines) {
      String trimmed = line.trim();
      if (trimmed.startsWith(prefix)) {
        try {
          return Integer.parseInt(trimmed.substring(prefix.length()).trim());
        }
        catch (NumberFormatException ignored) {
          return 0;
        }
      }
    }
    return 0;
  }

  /**
   * 查询 Application 状态。
   * 默认 --engine local：仅读取本地状态文件、检查 PID 和端口；进程不存在就清理过期状态并返回 STOPPED，绝不调用 MCP。
   * 显式 --engine idea-mcp：仅连接已运行的 MCP，不可用直接报错，绝不自动启动 IDEA。
   *
   * @param command 命令参数
   * @return 状态结果键值对
   * @throws Exception 处理异常
   */
  static Map<String, Object> queryStatus(Command command) throws Exception {
    Path project = command.project();
    String configName = command.option("configuration", "Application");
    String engine = command.option("engine", "local");

    if ("idea-mcp".equalsIgnoreCase(engine)) {
      McpClient mcp = connectMcp(project);
      if (mcp == null) {
        Map<String, Object> err = new LinkedHashMap<String, Object>();
        err.put("success", false);
        err.put("engine", "idea-mcp");
        err.put("error", "IDEA MCP 未运行（端口 " + mcpPort() + " 未就绪）");
        return err;
      }
      Map<String, Object> args = new LinkedHashMap<String, Object>();
      args.put("projectPath", project.toString());
      args.put("name", configName);
      args.put("action", "status");
      try {
        String result = mcp.callToolViaSse("control_run_configuration", args);
        return parseMcpRunResult(result, project, configName, "status");
      }
      catch (IOException e) {
        Map<String, Object> err = new LinkedHashMap<String, Object>();
        err.put("success", false);
        err.put("engine", "idea-mcp");
        err.put("error", "IDEA MCP 状态查询失败: " + e.getMessage());
        return err;
      }
    }

    // 默认 local 模式：只读取本地状态文件、检查 PID 和端口；进程不存在就清理过期状态并返回 STOPPED，不调用 MCP
    Path stateFile = runState(project, configName);
    RunState state = RunState.read(stateFile);
    if (state != null) {
      boolean processAlive = state.pid > 0 && isProcessAlive(state.pid);
      if (processAlive) {
        int port = resolvePort(state);
        if (port > 0) {
          boolean portOpen = isPortOpen(port);
          String st = portOpen ? "RUNNING" : "STARTING";
          Map<String, Object> map = state.withPort(port).toMap(st);
          map.put("engine", "local");
          return map;
        } else {
          Map<String, Object> map = state.toMap("RUNNING");
          map.put("engine", "local");
          return map;
        }
      } else {
        // 进程不存在，清理过期状态文件
        if (stateFile != null) {
          Files.deleteIfExists(stateFile);
        }
      }
    }

    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("projectPath", project.toString());
    result.put("configuration", configName);
    result.put("status", "STOPPED");
    result.put("running", false);
    result.put("engine", "local");
    return result;
  }

  /** 查询 Application 状态。 */
  private static void status(Command command) throws Exception {
    Map<String, Object> result = queryStatus(command);
    System.out.println(json(result));
    if (Boolean.FALSE.equals(result.get("success"))) {
      System.exit(1);
    }
  }

  /**
   * 查询构建状态：返回真实阶段、PID、耗时、日志路径与失败/超时/取消状态，不伪造百分比或 ETA。
   *
   * @param command 命令参数
   * @throws Exception 处理异常
   */
  private static void buildStatus(Command command) throws Exception {
    Path project = command.optionalProject();
    String buildId = command.option("build", "latest");
    int tailLines = Integer.parseInt(command.option("tail", "0"));
    Map<String, Object> state = BuildTracker.read(project, buildId);
    if (state == null) {
      Map<String, Object> err = new LinkedHashMap<String, Object>();
      err.put("success", false);
      err.put("errorCode", "BUILD_NOT_FOUND");
      err.put("error", "未找到构建状态记录（build=" + buildId + "）");
      err.put("projectPath", project.toString());
      System.out.println(json(err));
      System.exit(1);
      return;
    }
    Map<String, Object> result = new LinkedHashMap<String, Object>(state);
    result.put("success", true);
    result.put("buildState", state.get("state"));
    if (tailLines > 0) {
      Object logFile = state.get("logFile");
      if (logFile != null) {
        try {
          result.put("logTail", String.join("\n", tail(Paths.get(logFile.toString()), tailLines)));
        }
        catch (Exception ignored) {
        }
      }
    }
    System.out.println(json(result));
  }

  /** 打印工具、jps-cli 与缓存格式版本，便于安装版可追溯。 */
  static void printVersion() {
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("tool", "simple-ide");
    result.put("version", TOOL_VERSION);
    result.put("cacheFormatVersion", CACHE_FORMAT_VERSION);
    result.put("javaVersion", System.getProperty("java.version"));
    result.put("javaHome", System.getProperty("java.home"));
    Path jpsJar = findJpsCliJar();
    if (jpsJar != null) {
      result.put("jpsCliJar", jpsJar.toAbsolutePath().toString());
      result.put("jpsCliJarSize", jpsJar.toFile().length());
      result.put("jpsCliJarSha256", sha256(jpsJar));
    }
    else {
      result.put("jpsCliJar", null);
    }
    System.out.println(json(result));
  }

  /**
   * 计算文件 SHA-256，用于验证分发构建与安装版本一致。
   *
   * @param file 文件路径
   * @return 十六进制摘要；读取失败返回 null
   */
  static String sha256(Path file) {
    try {
      java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(Files.readAllBytes(file));
      StringBuilder sb = new StringBuilder();
      for (byte b : hash) sb.append(String.format("%02x", b));
      return sb.toString();
    }
    catch (Exception e) {
      return null;
    }
  }

  /**
   * 停止 Application 进程。
   * 默认 --engine local 时仅停止本地运行进程，进程不存在直接返回已停止，绝不回退到 IDEA。
   * 显式 --engine idea-mcp 时仅连接已运行的 MCP 发送停止指令，未运行直接报错。
   *
   * @param command 命令参数
   * @return 停止结果键值对
   * @throws Exception 处理异常
   */
  static Map<String, Object> executeStop(Command command) throws Exception {
    Path project = command.project();
    String configName = command.option("configuration", "Application");
    String engine = command.option("engine", "local");
    Path stateFile = runState(project, configName);
    RunState state = RunState.read(stateFile);

    if ("idea-mcp".equalsIgnoreCase(engine)) {
      McpClient mcp = connectMcp(project);
      if (mcp == null) {
        Map<String, Object> err = new LinkedHashMap<String, Object>();
        err.put("success", false);
        err.put("engine", "idea-mcp");
        err.put("error", "IDEA MCP 未运行（端口 " + mcpPort() + " 未就绪）");
        return err;
      }
      Map<String, Object> args = new LinkedHashMap<String, Object>();
      args.put("projectPath", project.toString());
      args.put("name", configName);
      args.put("action", "stop");
      try {
        String result = mcp.callToolViaSse("control_run_configuration", args);
        if (stateFile != null) {
          Files.deleteIfExists(stateFile);
        }
        return parseMcpRunResult(result, project, configName, "stop");
      }
      catch (IOException e) {
        Map<String, Object> err = new LinkedHashMap<String, Object>();
        err.put("success", false);
        err.put("engine", "idea-mcp");
        err.put("error", "IDEA MCP 停止应用失败: " + e.getMessage());
        return err;
      }
    }

    // 默认 local 模式：
    if (state != null && state.pid > 0 && isProcessAlive(state.pid)) {
      signalProcessGroup(state.pid, "TERM");
      for (int attempt = 0; attempt < 50 && isProcessAlive(state.pid); attempt++) {
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
      }
      if (isProcessAlive(state.pid)) {
        signalProcessGroup(state.pid, "KILL");
      }
      if (stateFile != null) {
        Files.deleteIfExists(stateFile);
      }
      Map<String, Object> map = new LinkedHashMap<String, Object>();
      map.put("stopped", true);
      map.put("configuration", configName);
      map.put("projectPath", project.toString());
      map.put("engine", "local");
      return map;
    }

    // 停止不存在的本地进程应直接返回已停止，不能回退到 IDEA
    if (stateFile != null) {
      Files.deleteIfExists(stateFile);
    }
    Map<String, Object> map = new LinkedHashMap<String, Object>();
    map.put("stopped", true);
    map.put("configuration", configName);
    map.put("projectPath", project.toString());
    map.put("engine", "local");
    return map;
  }

  /** 停止 Application。 */
  private static void stop(Command command) throws Exception {
    Map<String, Object> result = executeStop(command);
    System.out.println(json(result));
    if (Boolean.FALSE.equals(result.get("success"))) {
      System.exit(1);
    }
  }

  /**
   * 将测试结果写入持久化报告文件，并更新最新报告快捷文件，同时向结果中注入 reportFile 绝对路径。
   *
   * @param project 目标项目根目录
   * @param testClass 测试类全限定名
   * @param resultMap 测试结果键值对
   * @return 生成的测试报告文件绝对路径
   */
  static Path writeTestReport(Path project, String testClass, Map<String, Object> resultMap) throws IOException {
    Path reportDir = project.resolve(".simple-ide/reports");
    Files.createDirectories(reportDir);

    String timestamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
    String safeClass = testClass != null && testClass.contains(".")
      ? testClass.substring(testClass.lastIndexOf('.') + 1)
      : (testClass != null ? testClass : "unknown");

    Path reportFile = reportDir.resolve(String.format("test-%s-%s.json", safeClass, timestamp));
    Path latestReportFile = reportDir.resolve("test-report.json");

    resultMap.put("reportFile", reportFile.toAbsolutePath().toString());
    String content = json(resultMap);

    Files.write(reportFile, content.getBytes(StandardCharsets.UTF_8));
    try {
      Files.write(latestReportFile, content.getBytes(StandardCharsets.UTF_8));
    } catch (Exception ignored) {}

    return reportFile;
  }

  /**
   * 将 JSON 格式的测试结果注入 reportFile 字段，写入持久化报告文件与最新快捷文件，并返回更新后的 JSON 字符串。
   *
   * @param project 目标项目根目录
   * @param testClass 测试类全限定名
   * @param jsonResult 原始测试 JSON 字符串
   * @return 包含 reportFile 字段的完整 JSON 字符串
   */
  static String attachReportFileAndWrite(Path project, String testClass, String jsonResult) {
    if (project == null || jsonResult == null || !jsonResult.trim().startsWith("{")) return jsonResult;
    try {
      Path reportDir = project.resolve(".simple-ide/reports");
      Files.createDirectories(reportDir);

      String timestamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
      String safeClass = testClass != null && testClass.contains(".")
        ? testClass.substring(testClass.lastIndexOf('.') + 1)
        : (testClass != null ? testClass : "unknown");

      Path reportFile = reportDir.resolve(String.format("test-%s-%s.json", safeClass, timestamp));
      Path latestReportFile = reportDir.resolve("test-report.json");

      String trimmed = jsonResult.trim();
      int lastBrace = trimmed.lastIndexOf('}');
      String finalJson = trimmed.substring(0, lastBrace) + ",\"reportFile\":" + json(reportFile.toAbsolutePath().toString()) + "}";

      Files.write(reportFile, finalJson.getBytes(StandardCharsets.UTF_8));
      try {
        Files.write(latestReportFile, finalJson.getBytes(StandardCharsets.UTF_8));
      } catch (Exception ignored) {}

      return finalJson;
    } catch (Exception ignored) {
      return jsonResult;
    }
  }

  /**
   * 组装测试子进程类路径，用内置运行器配套的 JUnit 替换项目中的同名框架依赖。
   * 保留项目输出、业务依赖及未由运行器提供的扩展，避免新 launcher 加载旧 commons/engine。
   *
   * @param projectClasspath 项目输出与依赖类路径
   * @param runtimeEntries 内置运行器及配套依赖
   * @return 不混用同名 JUnit 组件版本的测试类路径
   */
  static String buildTestClasspath(String projectClasspath, List<Path> runtimeEntries) {
    Set<String> bundledArtifacts = new LinkedHashSet<String>();
    for (Path entry : runtimeEntries) {
      String artifact = testFrameworkArtifact(entry);
      if (artifact != null) bundledArtifacts.add(artifact);
    }
    Set<String> entries = new LinkedHashSet<String>();
    for (String entry : projectClasspath.split(Pattern.quote(File.pathSeparator))) {
      if (entry.isEmpty()) continue;
      String artifact = testFrameworkArtifact(Paths.get(entry));
      if (artifact == null || !bundledArtifacts.contains(artifact)) entries.add(entry);
    }
    for (Path entry : runtimeEntries) entries.add(entry.toString());
    return String.join(File.pathSeparator, entries);
  }

  /** 返回测试框架 Jar 的无版本组件名，非框架依赖返回 null。 */
  private static String testFrameworkArtifact(Path entry) {
    if (entry.getFileName() == null) return null;
    Matcher matcher = Pattern.compile("^(junit(?:-[a-z-]+)?|opentest4j|apiguardian-api)-[0-9].*\\.jar$")
      .matcher(entry.getFileName().toString());
    return matcher.matches() ? matcher.group(1) : null;
  }

  /**
   * 通过内置 JPS 增量编译产物与本地 JUnit 平台执行测试。
   *
   * <p>可靠性约束：按目标模块解析精确 Classpath，并统一内置 JUnit 组件版本；子进程有边界超时并回收进程树；
   * 测试结果用真实 JSON 解析判定 success，不再用字符串 contains 猜测。
   *
   * @param project 目标项目根目录
   * @param testClass 测试类全限定名
   * @param method 可选的测试方法名
   * @param moduleHint 目标模块（可为 null，自动从测试源码路径推断）
   * @param timeoutSec 超时时间（秒）
   * @return 测试执行完成状态与结果
   */
  private static TestStatus testViaJps(Path project, String testClass, String method, String moduleHint, int timeoutSec,
                                      BuildStatus preBuildStatus) {
    Path pomFile = project.resolve("pom.xml");
    if (!Files.isRegularFile(pomFile)) return new TestStatus(false, false, null);

    boolean isolatedBuild = preBuildStatus != null && preBuildStatus.isolatedBuild;
    Map<String, String> isoClasses = isolatedBuild ? preBuildStatus.isolatedClasses : null;
    Map<String, String> isoTestClasses = isolatedBuild ? preBuildStatus.isolatedTestClasses : null;
    if (isolatedBuild) {
      System.err.println("[simple-ide] 测试将使用受保护隔离构建的输出（共享 target 未被更新）");
    }

    StringBuilder cpBuilder = new StringBuilder();
    try {
      ClasspathPlan plan = resolveProjectClasspath(project, moduleHint, true, isoClasses, isoTestClasses);
      for (String warning : plan.warnings) {
        System.err.println("[WARN] " + warning);
      }
      cpBuilder.append(plan.classpath);
    }
    catch (IOException e) {
      Map<String, Object> errMap = new LinkedHashMap<String, Object>();
      errMap.put("success", false);
      errMap.put("testClass", testClass);
      if (method != null) errMap.put("method", method);
      errMap.put("exitCode", 1);
      errMap.put("errorCode", "CLASSPATH_RESOLUTION_FAILED");
      errMap.put("error", e.getMessage());
      errMap.put("engine", "jps-local");
      try { writeTestReport(project, testClass, errMap); } catch (Exception ignored) {}
      String errJson = json(errMap);
      System.out.println(errJson);
      return new TestStatus(true, false, errJson);
    }

    // 引入 simple-ide 运行时 lib，并替换项目内与运行器冲突的 JUnit 组件
    List<Path> runtimeEntries = new ArrayList<Path>();
    try {
      URL location = SimpleIde.class.getProtectionDomain().getCodeSource().getLocation();
      if (location != null) {
        Path codePath = Paths.get(location.toURI());
        Path dir = Files.isDirectory(codePath) ? codePath : codePath.getParent();
        if (dir != null) {
          File[] jars = dir.toFile().listFiles((d, name) -> name.endsWith(".jar"));
          if (jars != null) {
            for (File j : jars) {
              runtimeEntries.add(j.toPath().toAbsolutePath());
            }
          }
        }
      }
    }
    catch (Exception ignored) {}

    List<String> processCommand = new ArrayList<String>();
    processCommand.add("java");
    processCommand.add("-cp");
    processCommand.add(buildTestClasspath(cpBuilder.toString(), runtimeEntries));
    processCommand.add("io.simpleide.LocalTestRunner");
    processCommand.add(testClass);
    if (method != null) {
      processCommand.add(method);
    }

    ProcessRunner.Result result = ProcessRunner.run(processCommand, project, timeoutSec, false,
      ProcessRunner.DEFAULT_MAX_OUTPUT_BYTES, null, null);

    if (!result.started) {
      return new TestStatus(false, false, null);
    }
    if (!result.finished) {
      Map<String, Object> timeoutMap = new LinkedHashMap<String, Object>();
      timeoutMap.put("success", false);
      timeoutMap.put("testClass", testClass);
      if (method != null) timeoutMap.put("method", method);
      timeoutMap.put("exitCode", 1);
      timeoutMap.put("errorCode", result.cancelled ? "CANCELLED" : "TIMEOUT");
      timeoutMap.put("error", "测试执行超时/取消 (" + timeoutSec + "s，已终止子进程树，killComplete=" + result.killComplete + ")");
      timeoutMap.put("engine", "jps-local");
      try { writeTestReport(project, testClass, timeoutMap); } catch (Exception ignored) {}
      String timeoutJson = json(timeoutMap);
      System.out.println(timeoutJson);
      return new TestStatus(true, false, timeoutJson);
    }

    // 真实 JSON 解析：从 stdout 中取最后一个可解析的 JSON 对象
    String lastJsonLine = lastJsonObjectLine(result.stdout);
    if (lastJsonLine != null) {
      Map<String, Object> parsed = JsonParser.tryParseObject(lastJsonLine);
      String merged = iterateIsolationFields(lastJsonLine, preBuildStatus);
      String finalJson = attachReportFileAndWrite(project, testClass, merged);
      System.out.println(finalJson);
      boolean success = parsed != null && Boolean.TRUE.equals(parsed.get("success"));
      return new TestStatus(true, success, finalJson);
    }
    else {
      Map<String, Object> errMap = new LinkedHashMap<String, Object>();
      errMap.put("success", false);
      errMap.put("testClass", testClass);
      if (method != null) errMap.put("method", method);
      errMap.put("exitCode", result.exitCode);
      errMap.put("errorCode", "NO_RESULT_JSON");
      errMap.put("error", "未获取到测试结果 JSON: " + result.stdout);
      errMap.put("engine", "jps-local");
      try { writeTestReport(project, testClass, errMap); } catch (Exception ignored) {}
      String errJson = json(errMap);
      System.out.println(errJson);
      return new TestStatus(true, false, errJson);
    }
  }

  /**
   * 把额外字段合并进一个 JSON 对象字符串（保持原字段不变）。
   *
   * @param jsonText 原 JSON 对象
   * @param extra 额外字段
   * @return 合并后的 JSON；无法解析时原样返回
   */
  static String mergeJsonFields(String jsonText, Map<String, Object> extra) {
    if (jsonText == null || extra == null || extra.isEmpty()) return jsonText;
    String trimmed = jsonText.trim();
    if (!trimmed.startsWith("{")) return jsonText;
    int lastBrace = trimmed.lastIndexOf('}');
    if (lastBrace <= 0) return jsonText;
    String body = trimmed.substring(1, lastBrace);
    String extraBody = json(extra);
    extraBody = extraBody.substring(1, extraBody.length() - 1);
    if (body.trim().isEmpty()) return "{" + extraBody + "}";
    return "{" + body + "," + extraBody + "}";
  }

  /**
   * 从输出中取最后一个可被真实解析为 JSON 对象的行。
   *
   * @param stdout 子进程标准输出
   * @return 可解析的 JSON 行；没有时返回 null
   */
  static String lastJsonObjectLine(String stdout) {
    if (stdout == null || stdout.isEmpty()) return null;
    String[] lines = stdout.split("\n");
    for (int i = lines.length - 1; i >= 0; i--) {
      String line = lines[i].trim();
      if (!line.startsWith("{") || !line.endsWith("}")) continue;
      if (JsonParser.tryParseObject(line) != null) return line;
    }
    return null;
  }

  /**
   * 在项目根与各模块的 {@code src/test/java} 下定位测试源文件（多模块项目必需）。
   *
   * @param project 项目根目录
   * @param testClass 测试类全限定名
   * @param moduleHint 显式模块提示；命中时优先在该模块内查找
   * @return 测试源文件路径；未找到时返回 null
   */
  static Path resolveTestSourceFile(Path project, String testClass, String moduleHint) {
    if (testClass == null || testClass.trim().isEmpty()) return null;
    String relative = testClass.trim().replace('.', '/') + ".java";
    List<Path> candidates = new ArrayList<Path>();
    try {
      List<ModuleResolver.Module> modules = ModuleResolver.resolve(project);
      if (moduleHint != null && !moduleHint.trim().isEmpty()) {
        try {
          ModuleResolver.Module hinted = ModuleResolver.select(modules, moduleHint);
          if (hinted != null) candidates.add(hinted.testSourceDir.resolve(relative));
        }
        catch (IOException ignored) {
        }
      }
      for (ModuleResolver.Module module : modules) {
        Path candidate = module.testSourceDir.resolve(relative);
        if (!candidates.contains(candidate)) candidates.add(candidate);
      }
    }
    catch (IOException ignored) {
    }
    candidates.add(project.resolve("src/test/java").resolve(relative));
    for (Path candidate : candidates) {
      if (Files.isRegularFile(candidate)) return candidate;
    }
    return null;
  }

  /**
   * 从测试源码路径推断所在模块名（多模块项目用于精确限定测试编译与 Classpath 作用域）。
   *
   * @param project 项目根目录
   * @param testFile 测试源文件路径
   * @return 模块名；无法推断时返回 null
   */
  static String moduleOfTestSource(Path project, Path testFile) {
    try {
      List<ModuleResolver.Module> modules = ModuleResolver.resolve(project);
      if (modules.isEmpty()) return null;
      Path normalized = testFile.toAbsolutePath().normalize();
      ModuleResolver.Module best = null;
      int bestLength = -1;
      for (ModuleResolver.Module module : modules) {
        Path sourceRoot = module.testSourceDir.toAbsolutePath().normalize();
        if (normalized.startsWith(sourceRoot) && sourceRoot.toString().length() > bestLength) {
          best = module;
          bestLength = sourceRoot.toString().length();
        }
      }
      if (best != null) return best.name;
      return modules.size() == 1 ? modules.get(0).name : null;
    }
    catch (IOException e) {
      return null;
    }
  }

  /** 执行测试：优先本地 JPS 编译与 JUnit 平台执行，支持回退到 IDEA MCP。 */
  private static void test(Command command) throws Exception {
    Path project = command.project();
    String testClass = command.required("class");
    String method = command.option("method", null);
    String engine = command.option("engine", "jps");

    // 将全限定类名转为文件路径；多模块项目需在各模块的 src/test/java 下定位
    String filePath = "src/test/java/" + testClass.replace('.', '/') + ".java";
    Path testFile = resolveTestSourceFile(project, testClass, command.option("module", null));
    if (testFile == null) {
      throw new IOException("测试文件不存在：" + project.resolve(filePath) + "（已搜索项目根与各模块的 src/test/java）");
    }
    String relativeTestPath = toProjectRelative(project, testFile.toString());

    // 优先尝试基于 JPS 的本地测试引擎
    BuildStatus preBuildStatus = null;
    if ("jps".equalsIgnoreCase(engine)) {
      int timeoutSec = Integer.parseInt(command.option("timeout", "300"));
      String moduleHint = command.option("module", null);
      if (moduleHint == null || moduleHint.trim().isEmpty()) {
        // 未显式指定模块时，用目标测试文件所在模块（或唯一的 source root）作为作用域
        moduleHint = moduleOfTestSource(project, testFile);
      }
      if (!command.flag("skip-build")) {
        System.err.println("[INFO] 正在执行 JPS 前置编译校验...");
        boolean skipLock = command.flag("no-lock") || command.flag("skip-lock");
        List<String> buildModules = new ArrayList<String>();
        if (moduleHint != null && !moduleHint.trim().isEmpty()) buildModules.add(moduleHint);
        BuildStatus buildStatus;
        if (skipLock) {
          buildStatus = buildViaJps(project, buildModules, null, timeoutSec, false, false, false, false, false, false, null, null);
        } else {
          try (BuildLock ignored = BuildLock.acquire(project)) {
            buildStatus = buildViaJps(project, buildModules, null, timeoutSec, false, false, false, false, false, false, null, null);
          }
        }
        if (buildStatus != null && !buildStatus.success) {
          String failJson = formatTestPreBuildFailure(testClass, method, buildStatus.jsonResult);
          String finalJson = attachReportFileAndWrite(project, testClass, failJson);
          System.out.println(finalJson);
          System.exit(1);
          return;
        }
        preBuildStatus = buildStatus;
      }

      TestStatus testStatus = testViaJps(project, testClass, method, moduleHint, timeoutSec, preBuildStatus);
      if (testStatus.executed) {
        if (!testStatus.success) {
          System.exit(1);
        }
        return;
      }
      if ("jps".equals(command.option("engine", null))) {
        throw new IllegalStateException("JPS 本地测试引擎执行失败");
      }
    }

    // 回退到原先的 IDEA MCP 测试逻辑
    McpClient mcp = connectMcp(project);
    if (mcp == null) throw new IOException("IDEA MCP 不可用，且本地测试引擎未就绪");

    // 测试依赖正确的编译目标，先校验再执行前置编译
    verifyMcpTarget(mcp, project);

    // 前置步骤：强制执行全局增量编译校验，防止运行陈旧未更新的 class 文件
    if (!command.flag("skip-build")) {
      System.err.println("[INFO] 正在执行 IDEA MCP 前置编译校验...");
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
        String failJson = formatTestPreBuildFailure(testClass, method, buildStatus.jsonResult);
        String finalJson = attachReportFileAndWrite(project, testClass, failJson);
        System.out.println(finalJson);
        System.exit(1);
        return;
      }
    }

    // Step 1: 用 get_run_configurations(filePath=...) 获取 run points
    int line = findTestLine(mcp, project, relativeTestPath, testClass, method);

    // Step 2: 用 execute_run_configuration(filePath + line) 执行
    Map<String, Object> args = new LinkedHashMap<String, Object>();
    args.put("projectPath", project.toString());
    args.put("filePath", relativeTestPath);
    args.put("line", line);
    args.put("waitForExit", true);
    args.put("timeout", 300000); // 5 分钟超时

    try {
      String result = mcp.callToolViaSse("execute_run_configuration", args);
      String content = unwrapMcpContent(result);
      String rawJson = mcpTestResultToJson(content, testClass, method);
      String finalJson = attachReportFileAndWrite(project, testClass, rawJson);
      System.out.println(finalJson);
      boolean success = finalJson.contains("\"success\":true") || finalJson.contains("\"success\": true");
      if (!success) {
        System.exit(1);
      }
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
        if (tc.details != null) {
          failObj.put("details", tc.details);
          String firstLine = tc.details.split("\n")[0].trim();
          int colon = firstLine.indexOf(':');
          String exc = colon > 0 ? firstLine.substring(0, colon).trim() : firstLine;
          if (!exc.isEmpty() && !exc.startsWith("at ")) {
            failObj.put("exception", exc);
          }
        }
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
        if (fail.containsKey("exception")) outBuilder.append("\nException: ").append(fail.get("exception"));
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

  // ==================== report 子命令与动态管理工作台 ====================

  /** 动态管理控制台 HTTP 服务包装类。 */
  static final class DashboardServer {
    final HttpServer server;
    final java.util.concurrent.ExecutorService executor;
    final int port;

    DashboardServer(HttpServer server, java.util.concurrent.ExecutorService executor, int port) {
      this.server = server;
      this.executor = executor;
      this.port = port;
    }

    /** 停止 HTTP 服务并释放后台线程池资源。 */
    void stop() {
      if (server != null) {
        server.stop(0);
      }
      if (executor != null) {
        executor.shutdownNow();
      }
    }
  }

  /** 从输入流读取所有字节（JDK 8 兼容）。 */
  static byte[] readAllBytes(InputStream in) throws IOException {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    byte[] buf = new byte[4096];
    int n;
    while ((n = in.read(buf)) != -1) {
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  /** 停止指定本地应用进程并删除状态文件。 */
  static boolean stopApplication(Path project, String configuration) {
    if (project == null || configuration == null) return false;
    try {
      Path stateFile = runState(project, configuration);
      RunState state = RunState.read(stateFile);
      if (state == null || !isProcessAlive(state.pid)) {
        Files.deleteIfExists(stateFile);
        return false;
      }
      signalProcessGroup(state.pid, "TERM");
      for (int attempt = 0; attempt < 50 && isProcessAlive(state.pid); attempt++) {
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
      }
      if (isProcessAlive(state.pid)) signalProcessGroup(state.pid, "KILL");
      Files.deleteIfExists(stateFile);
      return true;
    } catch (Exception ignored) {
      return false;
    }
  }

  /** 启动本地动态管理 HTTP 服务，提供交互式管理界面与 REST API。 */
  static DashboardServer startDashboardServer(Path project, int port) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    server.createContext("/", exchange -> {
      String path = exchange.getRequestURI().getPath();
      if ("/".equals(path) || "/index.html".equals(path)) {
        byte[] bytes = generateDashboardHtml(project, true).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
      } else {
        exchange.sendResponseHeaders(404, -1);
      }
    });

    server.createContext("/api/status", exchange -> {
      List<Map<String, Object>> apps = collectAllApps(project);
      Map<String, Object> testReport = readRecentTestReport(project);
      List<Map<String, Object>> history = readCommandHistory(project, 50);

      Map<String, Object> data = new LinkedHashMap<String, Object>();
      data.put("project", project.toAbsolutePath().toString());
      data.put("apps", apps);
      if (testReport != null) data.put("testReport", testReport);
      data.put("history", history);
      data.put("timestamp", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));

      byte[] bytes = json(data).getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
      exchange.sendResponseHeaders(200, bytes.length);
      OutputStream os = exchange.getResponseBody();
      os.write(bytes);
      os.close();
    });

    server.createContext("/api/logs", exchange -> {
      String query = exchange.getRequestURI().getQuery();
      String configuration = null;
      String projectPathStr = null;
      int tailLines = 100;
      if (query != null) {
        for (String param : query.split("&")) {
          String[] kv = param.split("=");
          if (kv.length == 2) {
            if ("configuration".equals(kv[0])) configuration = urlDecode(kv[1]);
            else if ("projectPath".equals(kv[0])) projectPathStr = urlDecode(kv[1]);
            else if ("tail".equals(kv[0])) {
              try { tailLines = Integer.parseInt(kv[1]); } catch (Exception ignored) {}
            }
          }
        }
      }

      Map<String, Object> resp = new LinkedHashMap<String, Object>();
      if (configuration != null && !configuration.isEmpty()) {
        Path targetProject = project;
        if (projectPathStr != null && !projectPathStr.trim().isEmpty()) {
          Path p = Paths.get(projectPathStr).toAbsolutePath().normalize();
          if (Files.isDirectory(p)) targetProject = p;
        }
        Path log = targetProject.resolve(".simple-ide/runs/" + configuration + ".log");
        resp.put("configuration", configuration);
        resp.put("projectPath", targetProject.toAbsolutePath().toString());
        resp.put("lines", tail(log, tailLines));
      } else {
        resp.put("lines", new ArrayList<String>());
      }

      byte[] bytes = json(resp).getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
      exchange.sendResponseHeaders(200, bytes.length);
      OutputStream os = exchange.getResponseBody();
      os.write(bytes);
      os.close();
    });

    server.createContext("/api/stop", exchange -> {
      if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        exchange.sendResponseHeaders(405, -1);
        return;
      }

      String query = exchange.getRequestURI().getQuery();
      String configuration = null;
      String projectPathStr = null;
      if (query != null) {
        for (String param : query.split("&")) {
          String[] kv = param.split("=");
          if (kv.length == 2) {
            if ("configuration".equals(kv[0])) configuration = urlDecode(kv[1]);
            else if ("projectPath".equals(kv[0])) projectPathStr = urlDecode(kv[1]);
          }
        }
      }

      Path targetProject = project;
      if (projectPathStr != null && !projectPathStr.trim().isEmpty()) {
        Path p = Paths.get(projectPathStr).toAbsolutePath().normalize();
        if (Files.isDirectory(p)) targetProject = p;
      }

      boolean stopped = stopApplication(targetProject, configuration);
      Map<String, Object> resp = new LinkedHashMap<String, Object>();
      resp.put("success", stopped);
      resp.put("stopped", stopped);
      resp.put("configuration", configuration);
      resp.put("projectPath", targetProject.toAbsolutePath().toString());

      byte[] bytes = json(resp).getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
      exchange.sendResponseHeaders(200, bytes.length);
      OutputStream os = exchange.getResponseBody();
      os.write(bytes);
      os.close();
    });

    java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newCachedThreadPool();
    server.setExecutor(executor);
    server.start();
    return new DashboardServer(server, executor, port);
  }

  /** 执行 report 命令：启动动态管理工作台（默认）或导出静态 HTML 报告。 */
  private static void report(Command command) throws Exception {
    Path project = command.optionalProject();
    boolean isStatic = command.flag("static");

    if (isStatic) {
      Path htmlReport = generateHtmlReport(project);
      boolean noOpen = command.flag("no-open");
      boolean opened = !noOpen && openInBrowser(htmlReport);

      Map<String, Object> resp = new LinkedHashMap<String, Object>();
      resp.put("success", true);
      resp.put("project", project.toAbsolutePath().toString());
      resp.put("reportFile", htmlReport.toAbsolutePath().toString());
      resp.put("url", htmlReport.toUri().toString());
      resp.put("opened", opened);
      resp.put("mode", "static");
      System.out.println(json(resp));
      return;
    }

    int port = -1;
    String portOpt = command.option("port", null);
    if (portOpt != null) {
      try { port = Integer.parseInt(portOpt); } catch (Exception ignored) {}
    }
    if (port <= 0) {
      port = isPortAvailable(63100) ? 63100 : findAvailablePort();
    }

    DashboardServer server = startDashboardServer(project, port);
    String url = "http://localhost:" + port;
    boolean noOpen = command.flag("no-open");
    boolean opened = !noOpen && openUrlInBrowser(url);

    Map<String, Object> resp = new LinkedHashMap<String, Object>();
    resp.put("success", true);
    resp.put("project", project.toAbsolutePath().toString());
    resp.put("url", url);
    resp.put("port", port);
    resp.put("opened", opened);
    resp.put("mode", "live-server");
    System.out.println(json(resp));

    if (!command.flag("daemon")) {
      System.err.println("[INFO] Simple IDE 动态管理控制台已启动: " + url + " (按 Ctrl+C 停止服务)");
      try {
        Thread.currentThread().join();
      } catch (InterruptedException ignored) {
        server.stop();
      }
    }
  }

  /** 尝试通过系统默认浏览器打开文件。 */
  static boolean openInBrowser(Path file) {
    if (file == null || !Files.isRegularFile(file)) return false;
    return openUrlInBrowser(file.toAbsolutePath().toString());
  }

  /** 尝试通过系统默认浏览器打开指定 URL。 */
  static boolean openUrlInBrowser(String url) {
    if (url == null || url.isEmpty()) return false;
    try {
      String os = System.getProperty("os.name", "").toLowerCase();
      Process process;
      if (os.contains("mac")) {
        process = new ProcessBuilder("open", url).start();
      } else if (os.contains("win")) {
        process = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
      } else {
        process = new ProcessBuilder("xdg-open", url).start();
      }
      return process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0;
    } catch (Exception ignored) {
      return false;
    }
  }

  /** 向项目的命令执行历史追加一行记录。 */
  static void recordCommand(Path project, String command, String argsStr, boolean success, long durationMs, String summary) {
    if (project == null) return;
    try {
      Path simpleIdeDir = project.resolve(".simple-ide");
      Files.createDirectories(simpleIdeDir);
      Path historyFile = simpleIdeDir.resolve("history.jsonl");

      String timeStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
      Map<String, Object> record = new LinkedHashMap<String, Object>();
      record.put("time", timeStr);
      record.put("command", command);
      record.put("args", argsStr != null ? argsStr : "");
      record.put("success", success);
      record.put("durationMs", durationMs);
      if (summary != null && !summary.isEmpty()) {
        record.put("summary", summary);
      }

      String line = json(record) + "\n";
      Files.write(historyFile, line.getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    } catch (Exception ignored) {}
  }

  /** 读取项目的最近命令执行历史（按时间倒序排列）。 */
  static List<Map<String, Object>> readCommandHistory(Path project, int limit) {
    List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
    if (project == null) return list;
    Path historyFile = project.resolve(".simple-ide/history.jsonl");
    if (!Files.isRegularFile(historyFile)) return list;
    try {
      List<String> lines = Files.readAllLines(historyFile, StandardCharsets.UTF_8);
      for (int i = lines.size() - 1; i >= 0 && list.size() < limit; i--) {
        String line = lines.get(i).trim();
        if (line.isEmpty() || !line.startsWith("{")) continue;
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("time", extractJsonString(line, "time"));
        map.put("command", extractJsonString(line, "command"));
        map.put("args", extractJsonString(line, "args"));
        map.put("success", line.contains("\"success\":true") || line.contains("\"success\": true"));
        String dur = extractJsonNumber(line, "durationMs");
        map.put("durationMs", dur != null ? Long.parseLong(dur) : 0L);
        String summary = extractJsonString(line, "summary");
        if (summary != null) map.put("summary", summary);
        list.add(map);
      }
    } catch (Exception ignored) {}
    return list;
  }

  /** 收集项目中已配置或已运行的关联应用。 */
  static List<Map<String, Object>> collectAssociatedApps(Path project) {
    List<Map<String, Object>> apps = new ArrayList<Map<String, Object>>();
    if (project == null) return apps;
    Path runsDir = project.resolve(".simple-ide/runs");
    if (!Files.isDirectory(runsDir)) return apps;
    File[] propFiles = runsDir.toFile().listFiles((d, name) -> name.endsWith(".properties"));
    if (propFiles == null) return apps;

    for (File f : propFiles) {
      try {
        String configName = f.getName().substring(0, f.getName().length() - ".properties".length());
        RunState state = RunState.read(f.toPath());
        if (state == null) continue;
        boolean alive = isProcessAlive(state.pid);
        // 端口沿用状态文件记录值（停止后仍可查看原端口）；未记录且进程存活时才自动探测监听端口
        int port = state.port > 0 ? state.port : (alive ? resolvePort(state) : -1);
        boolean portListening = alive && port > 0 && isPortOpen(port);

        Map<String, Object> app = new LinkedHashMap<String, Object>();
        app.put("configuration", configName);
        app.put("type", configName.startsWith("npm-") ? "npm" : "java");
        app.put("projectPath", project.toAbsolutePath().toString());
        app.put("pid", state.pid);
        app.put("status", alive ? (portListening ? "RUNNING" : "STARTING") : "STOPPED");
        app.put("port", port);
        app.put("debugPort", state.debugPort);
        app.put("log", state.log.toAbsolutePath().toString());
        app.put("alive", alive);
        apps.add(app);
      } catch (Exception ignored) {}
    }
    return apps;
  }

  /**
   * 对 URL 编码字符串进行安全解码。
   *
   * @param val 待解码字符串
   * @return 解码后的普通字符串
   */
  static String urlDecode(String val) {
    if (val == null) return null;
    try {
      return java.net.URLDecoder.decode(val, "UTF-8");
    } catch (Exception e) {
      return val;
    }
  }

  /**
   * 收集全系统所有活跃 simple-ide 项目中的关联应用与服务（跨工程全景感知）。
   *
   * @param currentProject 当前所在项目根路径
   * @return 汇聚后的关联应用列表
   */
  static List<Map<String, Object>> collectAllApps(Path currentProject) {
    List<Path> projects = resolveProjects(Command.parse(new String[]{"list"}), null);
    if (currentProject != null) {
      Path normCurrent = currentProject.toAbsolutePath().normalize();
      if (!projects.contains(normCurrent)) {
        projects.add(0, normCurrent);
      }
    }

    List<Map<String, Object>> allApps = new ArrayList<Map<String, Object>>();
    Set<String> visitedKeys = new LinkedHashSet<String>();

    for (Path project : projects) {
      if (!Files.isDirectory(project)) continue;
      List<Map<String, Object>> apps = collectAssociatedApps(project);
      for (Map<String, Object> app : apps) {
        String key = app.get("projectPath") + "#" + app.get("configuration");
        if (visitedKeys.add(key)) {
          allApps.add(app);
        }
      }
    }
    return allApps;
  }

  /** 读取最近一次测试报告结果。 */
  static Map<String, Object> readRecentTestReport(Path project) {
    if (project == null) return null;
    Path reportFile = project.resolve(".simple-ide/reports/test-report.json");
    if (!Files.isRegularFile(reportFile)) return null;
    try {
      String content = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
      Map<String, Object> map = new LinkedHashMap<String, Object>();
      map.put("testClass", extractJsonString(content, "testClass"));
      map.put("success", content.contains("\"success\":true") || content.contains("\"success\": true"));
      String total = extractJsonNumber(content, "total");
      String passed = extractJsonNumber(content, "passed");
      String failed = extractJsonNumber(content, "failed");
      String duration = extractJsonNumber(content, "duration");
      map.put("total", total != null ? Integer.parseInt(total) : 0);
      map.put("passed", passed != null ? Integer.parseInt(passed) : 0);
      map.put("failed", failed != null ? Integer.parseInt(failed) : 0);
      map.put("duration", duration != null ? Long.parseLong(duration) : 0L);
      map.put("rawJson", content);
      return map;
    } catch (Exception ignored) {
      return null;
    }
  }

  /** 对 HTML 文本中的特殊字符进行实体编码。 */
  static String escapeHtml(String text) {
    if (text == null) return "";
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '&') sb.append("&amp;");
      else if (c == '<') sb.append("&lt;");
      else if (c == '>') sb.append("&gt;");
      else if (c == '"') sb.append("&quot;");
      else if (c == '\'') sb.append("&#39;");
      else sb.append(c);
    }
    return sb.toString();
  }

  /**
   * 生成当前项目的 HTML 仪表盘报告内容。
   *
   * @param project 目标项目根目录
   * @param isDynamic 是否启用动态轮询与交互功能
   * @return HTML 页面完整字符串
   */
  static String generateDashboardHtml(Path project, boolean isDynamic) {
    List<Map<String, Object>> apps = collectAllApps(project);
    Map<String, Object> testReport = readRecentTestReport(project);
    List<Map<String, Object>> history = readCommandHistory(project, 50);

    String nowStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
    String projectPathStr = project.toAbsolutePath().toString();

    StringBuilder sb = new StringBuilder();
    sb.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"UTF-8\">\n")
      .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
      .append("<title>Simple IDE - 管理工作台</title>\n<style>\n")
      .append(":root { --bg: #0f172a; --card-bg: #1e293b; --text: #f8fafc; --text-muted: #94a3b8; --border: #334155; --primary: #38bdf8; --success: #10b981; --danger: #ef4444; }\n")
      .append("* { box-sizing: border-box; margin: 0; padding: 0; }\n")
      .append("body { font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif; background: var(--bg); color: var(--text); padding: 24px; line-height: 1.5; }\n")
      .append(".container { max-width: 1200px; margin: 0 auto; }\n")
      .append("header { display: flex; justify-content: space-between; align-items: center; padding-bottom: 18px; border-bottom: 1px solid var(--border); margin-bottom: 24px; }\n")
      .append(".logo { font-size: 20px; font-weight: 700; color: var(--primary); display: flex; align-items: center; gap: 8px; }\n")
      .append(".header-meta { font-size: 13px; color: var(--text-muted); text-align: right; }\n")
      .append(".section { margin-bottom: 28px; }\n")
      .append(".section-title { font-size: 16px; font-weight: 600; margin-bottom: 12px; display: flex; align-items: center; gap: 8px; }\n")
      .append(".grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 14px; }\n")
      .append(".card { background: var(--card-bg); border: 1px solid var(--border); border-radius: 8px; padding: 16px; }\n")
      .append(".card-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }\n")
      .append(".card-title { font-size: 15px; font-weight: 600; }\n")
      .append(".badge { padding: 3px 8px; border-radius: 9999px; font-size: 11px; font-weight: 600; text-transform: uppercase; }\n")
      .append(".badge-running { background: rgba(16, 185, 129, 0.2); color: var(--success); }\n")
      .append(".badge-stopped { background: rgba(148, 163, 184, 0.15); color: var(--text-muted); }\n")
      .append(".badge-success { background: rgba(16, 185, 129, 0.2); color: var(--success); }\n")
      .append(".badge-failed { background: rgba(239, 68, 68, 0.2); color: var(--danger); }\n")
      .append(".prop-row { display: flex; justify-content: space-between; padding: 5px 0; border-bottom: 1px solid rgba(255, 255, 255, 0.05); font-size: 13px; }\n")
      .append(".prop-label { color: var(--text-muted); }\n")
      .append(".prop-val { font-family: monospace; }\n")
      .append(".prop-val a { color: var(--primary); text-decoration: none; }\n")
      .append(".btn { background: #334155; color: #fff; border: 1px solid var(--border); padding: 5px 12px; border-radius: 6px; cursor: pointer; font-size: 12px; text-decoration: none; display: inline-flex; align-items: center; gap: 4px; }\n")
      .append(".btn:hover { background: #475569; }\n")
      .append(".btn-danger { background: rgba(239, 68, 68, 0.2); color: var(--danger); border-color: rgba(239, 68, 68, 0.4); }\n")
      .append(".btn-danger:hover { background: rgba(239, 68, 68, 0.3); }\n")
      .append("table { width: 100%; border-collapse: collapse; background: var(--card-bg); border-radius: 8px; overflow: hidden; border: 1px solid var(--border); font-size: 13px; }\n")
      .append("th, td { padding: 10px 12px; text-align: left; }\n")
      .append("th { background: rgba(255, 255, 255, 0.03); color: var(--text-muted); font-weight: 600; border-bottom: 1px solid var(--border); }\n")
      .append("tr:not(:last-child) td { border-bottom: 1px solid var(--border); }\n")
      .append("code { font-family: monospace; background: rgba(255, 255, 255, 0.06); padding: 2px 5px; border-radius: 4px; font-size: 12px; }\n")
      .append(".empty-hint { padding: 20px; text-align: center; color: var(--text-muted); background: var(--card-bg); border-radius: 8px; border: 1px dashed var(--border); font-size: 13px; }\n")
      .append(".stats-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(100px, 1fr)); gap: 10px; margin-bottom: 14px; }\n")
      .append(".stat-box { background: rgba(255, 255, 255, 0.03); padding: 10px; border-radius: 6px; text-align: center; }\n")
      .append(".stat-num { font-size: 20px; font-weight: 700; }\n")
      .append(".stat-label { font-size: 11px; color: var(--text-muted); text-transform: uppercase; margin-top: 2px; }\n")
      .append(".failures-box { background: rgba(239, 68, 68, 0.08); border: 1px solid rgba(239, 68, 68, 0.2); border-radius: 6px; padding: 12px; margin-top: 10px; }\n")
      .append(".failure-item { margin-bottom: 6px; font-size: 13px; }\n")
      .append(".failure-name { font-weight: 600; color: var(--danger); }\n")
      .append("footer { margin-top: 32px; text-align: center; font-size: 12px; color: var(--text-muted); }\n")
      .append("</style>\n</head>\n<body>\n<div class=\"container\">\n")
      .append("<header>\n")
      .append("  <div class=\"logo\">⚡ Simple IDE ")
      .append(isDynamic ? "动态管理控制台" : "状态报告")
      .append("</div>\n  <div class=\"header-meta\">项目：<code>").append(escapeHtml(projectPathStr)).append("</code><br>")
      .append(isDynamic ? "<span id=\"sync-indicator\" style=\"color:#10b981;\">●</span> <span id=\"sync-status\">实时连接中</span> ｜ 刷新于 <span id=\"sync-time\">" + escapeHtml(nowStr) + "</span> <button onclick=\"refreshStatus()\" class=\"btn\" style=\"margin-left:6px;\">立即刷新</button>" : "生成时间：" + escapeHtml(nowStr))
      .append("</div>\n</header>\n");

    // 1. 关联应用板块
    sb.append("<div class=\"section\">\n  <div class=\"section-title\">🚀 关联应用与服务 (Associated Applications)</div>\n  <div id=\"apps-container\">\n");
    if (apps.isEmpty()) {
      sb.append("    <div class=\"empty-hint\">暂无关联的应用配置</div>\n");
    } else {
      sb.append("    <div class=\"grid\">\n");
      for (Map<String, Object> app : apps) {
        String status = String.valueOf(app.get("status"));
        boolean isRunning = "RUNNING".equals(status);
        String name = String.valueOf(app.get("configuration"));
        String projPath = String.valueOf(app.get("projectPath") != null ? app.get("projectPath") : "");
        sb.append("      <div class=\"card\">\n")
          .append("        <div class=\"card-head\">\n")
          .append("          <span class=\"card-title\">").append(escapeHtml(name)).append("</span>\n")
          .append("          <span class=\"badge ").append(isRunning ? "badge-running" : "badge-stopped").append("\">").append(escapeHtml(status)).append("</span>\n")
          .append("        </div>\n");
        if (!projPath.isEmpty()) {
          sb.append("        <div class=\"prop-row\"><span class=\"prop-label\">所属工程</span><span class=\"prop-val\"><code>").append(escapeHtml(projPath)).append("</code></span></div>\n");
        }
        sb.append("        <div class=\"prop-row\"><span class=\"prop-label\">进程 PID</span><span class=\"prop-val\">").append(app.get("pid")).append("</span></div>\n");
        if (app.get("port") != null && ((Number) app.get("port")).intValue() > 0) {
          int port = ((Number) app.get("port")).intValue();
          sb.append("        <div class=\"prop-row\"><span class=\"prop-label\">HTTP 端口</span><span class=\"prop-val\"><a href=\"http://localhost:")
            .append(port).append("\" target=\"_blank\">http://localhost:").append(port).append("</a></span></div>\n");
        }
        if (app.get("debugPort") != null && ((Number) app.get("debugPort")).intValue() > 0) {
          sb.append("        <div class=\"prop-row\"><span class=\"prop-label\">JDWP 调试端口</span><span class=\"prop-val\">").append(app.get("debugPort")).append("</span></div>\n");
        }
        if (app.get("log") != null) {
          sb.append("        <div class=\"prop-row\"><span class=\"prop-label\">日志文件</span><span class=\"prop-val\"><code>").append(escapeHtml(String.valueOf(app.get("log")))).append("</code></span></div>\n");
        }
        if (isDynamic) {
          sb.append("        <div style=\"margin-top:12px; display:flex; gap:8px;\">\n")
            .append("          <button onclick=\"showLogs('").append(escapeHtml(name)).append("', '").append(escapeHtml(projPath)).append("')\" class=\"btn\">实时日志</button>\n");
          if (isRunning) {
            sb.append("          <button onclick=\"stopApp('").append(escapeHtml(name)).append("', '").append(escapeHtml(projPath)).append("')\" class=\"btn btn-danger\">停止</button>\n");
          }
          sb.append("        </div>\n");
        }
        sb.append("      </div>\n");
      }
      sb.append("    </div>\n");
    }
    sb.append("  </div>\n</div>\n");

    // 2. 最近结果板块 (测试)
    sb.append("<div class=\"section\">\n  <div class=\"section-title\">🧪 最近执行结果 (Recent Results)</div>\n");
    if (testReport == null) {
      sb.append("  <div class=\"empty-hint\">暂无最近测试执行报告</div>\n");
    } else {
      boolean testSuccess = Boolean.TRUE.equals(testReport.get("success"));
      sb.append("  <div class=\"card\">\n")
        .append("    <div class=\"card-head\">\n")
        .append("      <span class=\"card-title\">单元测试：").append(escapeHtml(String.valueOf(testReport.get("testClass")))).append("</span>\n")
        .append("      <span class=\"badge ").append(testSuccess ? "badge-success" : "badge-failed").append("\">")
        .append(testSuccess ? "PASSED" : "FAILED").append("</span>\n")
        .append("    </div>\n")
        .append("    <div class=\"stats-grid\">\n")
        .append("      <div class=\"stat-box\"><div class=\"stat-num\">").append(testReport.get("total")).append("</div><div class=\"stat-label\">总用例</div></div>\n")
        .append("      <div class=\"stat-box\"><div class=\"stat-num\" style=\"color: var(--success);\">").append(testReport.get("passed")).append("</div><div class=\"stat-label\">通过</div></div>\n")
        .append("      <div class=\"stat-box\"><div class=\"stat-num\" style=\"color: var(--danger);\">").append(testReport.get("failed")).append("</div><div class=\"stat-label\">失败</div></div>\n")
        .append("      <div class=\"stat-box\"><div class=\"stat-num\">").append(testReport.get("duration")).append("ms</div><div class=\"stat-label\">耗时</div></div>\n")
        .append("    </div>\n");

      String rawJson = (String) testReport.get("rawJson");
      if (rawJson != null && rawJson.contains("\"failures\":[")) {
        sb.append("    <div class=\"failures-box\">\n      <div style=\"font-weight: 600; color: var(--danger); margin-bottom: 6px;\">失败用例详情</div>\n");
        for (String part : rawJson.split("\\{\"test\":")) {
          if (!part.startsWith("\"")) continue;
          int endTest = part.indexOf('"', 1);
          if (endTest > 1) {
            String testName = part.substring(1, endTest);
            String msg = extractJsonString("{\"msg\":" + part, "message");
            sb.append("      <div class=\"failure-item\">• <span class=\"failure-name\">").append(escapeHtml(testName)).append("</span>");
            if (msg != null && !msg.isEmpty()) {
              sb.append("：").append(escapeHtml(msg));
            }
            sb.append("</div>\n");
          }
        }
        sb.append("    </div>\n");
      }
      sb.append("  </div>\n");
    }
    sb.append("</div>\n");

    // 3. 运行过的命令历史板块
    sb.append("<div class=\"section\">\n  <div class=\"section-title\">📜 运行命令历史 (Command History)</div>\n");
    if (history.isEmpty()) {
      sb.append("  <div class=\"empty-hint\">暂无历史执行记录</div>\n");
    } else {
      sb.append("  <table>\n    <thead><tr><th>时间</th><th>命令</th><th>参数</th><th>状态</th><th>耗时</th><th>摘要</th></tr></thead>\n    <tbody id=\"history-tbody\">\n");
      for (Map<String, Object> h : history) {
        boolean ok = Boolean.TRUE.equals(h.get("success"));
        sb.append("      <tr>\n")
          .append("        <td>").append(escapeHtml(String.valueOf(h.get("time")))).append("</td>\n")
          .append("        <td><strong>").append(escapeHtml(String.valueOf(h.get("command")))).append("</strong></td>\n")
          .append("        <td><code>").append(escapeHtml(String.valueOf(h.get("args")))).append("</code></td>\n")
          .append("        <td><span class=\"badge ").append(ok ? "badge-success" : "badge-failed").append("\">").append(ok ? "SUCCESS" : "FAILED").append("</span></td>\n")
          .append("        <td>").append(h.get("durationMs")).append("ms</td>\n")
          .append("        <td>").append(escapeHtml(String.valueOf(h.get("summary") != null ? h.get("summary") : "-"))).append("</td>\n")
          .append("      </tr>\n");
      }
      sb.append("    </tbody>\n  </table>\n");
    }
    sb.append("</div>\n");

    // 日志 Modal
    if (isDynamic) {
      sb.append("<div id=\"log-modal\" style=\"display:none; position:fixed; top:0; left:0; width:100%; height:100%; background:rgba(0,0,0,0.7); z-index:9999; justify-content:center; align-items:center;\">\n")
        .append("  <div style=\"background:#1e293b; border:1px solid #334155; border-radius:8px; width:85%; max-width:960px; height:80%; display:flex; flex-direction:column; box-shadow:0 10px 25px rgba(0,0,0,0.5);\">\n")
        .append("    <div style=\"display:flex; justify-content:space-between; align-items:center; padding:12px 16px; border-bottom:1px solid #334155;\">\n")
        .append("      <h3 id=\"log-modal-title\" style=\"font-size:15px; font-weight:600; color:#38bdf8;\">实时日志</h3>\n")
        .append("      <div style=\"display:flex; align-items:center; gap:10px;\">\n")
        .append("        <label style=\"font-size:12px; color:#94a3b8; cursor:pointer;\"><input type=\"checkbox\" id=\"auto-scroll\" checked> 自动滚底</label>\n")
        .append("        <button onclick=\"fetchLogs()\" class=\"btn\">刷新</button>\n")
        .append("        <button onclick=\"closeLogModal()\" class=\"btn\" style=\"background:#475569;\">关闭</button>\n")
        .append("      </div>\n    </div>\n")
        .append("    <pre id=\"log-content\" style=\"flex:1; overflow-y:auto; padding:14px; margin:0; background:#0b1120; color:#38bdf8; font-family:monospace; font-size:12px; line-height:1.4; white-space:pre-wrap; word-break:break-all;\"></pre>\n")
        .append("  </div>\n</div>\n");

      sb.append("<script>\n")
        .append("let currentLogApp = null;\nlet currentLogProject = null;\nlet logTimer = null;\n")
        .append("function escapeHtml(s){return (s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\"/g,'&quot;');}\n")
        .append("async function refreshStatus(){\n")
        .append("  try{\n")
        .append("    const r=await fetch('/api/status'); if(!r.ok) throw new Error();\n")
        .append("    const d=await r.json(); updateApps(d.apps||[]); if(d.history) updateHistory(d.history);\n")
        .append("    document.getElementById('sync-time').innerText=d.timestamp||new Date().toLocaleTimeString();\n")
        .append("    document.getElementById('sync-indicator').style.color='#10b981';\n")
        .append("    document.getElementById('sync-status').innerText='实时连接中';\n")
        .append("  }catch(e){\n")
        .append("    document.getElementById('sync-indicator').style.color='#ef4444';\n")
        .append("    document.getElementById('sync-status').innerText='连接中断';\n")
        .append("  }\n}\n")
        .append("function updateApps(apps){\n")
        .append("  const c=document.getElementById('apps-container'); if(!c) return;\n")
        .append("  if(apps.length===0){c.innerHTML='<div class=\"empty-hint\">暂无关联的应用配置</div>'; return;}\n")
        .append("  let h='<div class=\"grid\">';\n")
        .append("  for(const a of apps){\n")
        .append("    const run=a.status==='RUNNING';\n")
        .append("    const proj=a.projectPath||'';\n")
        .append("    h+=`<div class=\"card\"><div class=\"card-head\"><span class=\"card-title\">${escapeHtml(a.configuration)}</span><span class=\"badge ${run?'badge-running':'badge-stopped'}\">${a.status}</span></div>`;\n")
        .append("    if(proj) h+=`<div class=\"prop-row\"><span class=\"prop-label\">所属工程</span><span class=\"prop-val\"><code>${escapeHtml(proj)}</code></span></div>`;\n")
        .append("    h+=`<div class=\"prop-row\"><span class=\"prop-label\">进程 PID</span><span class=\"prop-val\">${a.pid}</span></div>`;\n")
        .append("    if(a.port>0) h+=`<div class=\"prop-row\"><span class=\"prop-label\">HTTP 端口</span><span class=\"prop-val\"><a href=\"http://localhost:${a.port}\" target=\"_blank\">http://localhost:${a.port}</a></span></div>`;\n")
        .append("    if(a.debugPort>0) h+=`<div class=\"prop-row\"><span class=\"prop-label\">JDWP 调试端口</span><span class=\"prop-val\">${a.debugPort}</span></div>`;\n")
        .append("    if(a.log) h+=`<div class=\"prop-row\"><span class=\"prop-label\">日志文件</span><span class=\"prop-val\"><code>${escapeHtml(a.log)}</code></span></div>`;\n")
        .append("    h+=`<div style=\"margin-top:12px; display:flex; gap:8px;\"><button onclick=\"showLogs('${escapeHtml(a.configuration)}', '${escapeHtml(proj)}')\" class=\"btn\">实时日志</button>`;\n")
        .append("    if(run) h+=`<button onclick=\"stopApp('${escapeHtml(a.configuration)}', '${escapeHtml(proj)}')\" class=\"btn btn-danger\">停止</button>`;\n")
        .append("    h+=`</div></div>`;\n  }\n  h+='</div>'; c.innerHTML=h;\n}\n")
        .append("function updateHistory(history){\n")
        .append("  const tb=document.getElementById('history-tbody'); if(!tb) return;\n")
        .append("  let h=''; for(const row of history){\n")
        .append("    const ok=row.success;\n")
        .append("    h+=`<tr><td>${escapeHtml(row.time||'')}</td><td><strong>${escapeHtml(row.command||'')}</strong></td><td><code>${escapeHtml(row.args||'')}</code></td><td><span class=\"badge ${ok?'badge-success':'badge-failed'}\">${ok?'SUCCESS':'FAILED'}</span></td><td>${row.durationMs||0}ms</td><td>${escapeHtml(row.summary||'-')}</td></tr>`;\n")
        .append("  }\n  tb.innerHTML=h;\n}\n")
        .append("async function stopApp(name, projectPath){\n")
        .append("  if(!confirm('确定要停止应用 '+name+' 吗？')) return;\n")
        .append("  try{\n")
        .append("    let url='/api/stop?configuration='+encodeURIComponent(name);\n")
        .append("    if(projectPath) url+='&projectPath='+encodeURIComponent(projectPath);\n")
        .append("    const r=await fetch(url,{method:'POST'});\n")
        .append("    const d=await r.json(); refreshStatus();\n")
        .append("  }catch(e){alert('操作失败: '+e);}\n}\n")
        .append("function showLogs(name, projectPath){\n")
        .append("  currentLogApp=name;\n  currentLogProject=projectPath||'';\n")
        .append("  document.getElementById('log-modal-title').innerText=name+' 实时日志';\n")
        .append("  document.getElementById('log-modal').style.display='flex'; fetchLogs();\n")
        .append("  if(logTimer) clearInterval(logTimer); logTimer=setInterval(fetchLogs, 1500);\n}\n")
        .append("async function fetchLogs(){\n")
        .append("  if(!currentLogApp) return;\n")
        .append("  try{\n")
        .append("    let url='/api/logs?configuration='+encodeURIComponent(currentLogApp)+'&tail=150';\n")
        .append("    if(currentLogProject) url+='&projectPath='+encodeURIComponent(currentLogProject);\n")
        .append("    const r=await fetch(url);\n")
        .append("    const d=await r.json(); const box=document.getElementById('log-content');\n")
        .append("    box.innerText=(d.lines||[]).join('\\n')||'(暂无日志输出)';\n")
        .append("    if(document.getElementById('auto-scroll').checked) box.scrollTop=box.scrollHeight;\n")
        .append("  }catch(e){}\n}\n")
        .append("function closeLogModal(){\n")
        .append("  currentLogApp=null;\n  currentLogProject=null;\n  if(logTimer) clearInterval(logTimer);\n")
        .append("  document.getElementById('log-modal').style.display='none';\n}\n")
        .append("setInterval(refreshStatus, 3000);\n")
        .append("</script>\n");
    }

    sb.append("<footer>Simple IDE • Minimal & Fast Java CLI Tool</footer>\n")
      .append("</div>\n</body>\n</html>\n");
    return sb.toString();
  }

  /**
   * 生成当前项目的静态 HTML 报告文件。
   *
   * @param project 目标项目根目录
   * @return 生成的 report.html 绝对路径
   */
  static Path generateHtmlReport(Path project) throws IOException {
    Path reportsDir = project.resolve(".simple-ide/reports");
    Files.createDirectories(reportsDir);
    Path htmlFile = reportsDir.resolve("report.html");
    String html = generateDashboardHtml(project, false);
    Files.write(htmlFile, html.getBytes(StandardCharsets.UTF_8));
    return htmlFile;
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

  /** 启动 npm script；默认先校验依赖是否已安装，缺失时快速失败。 */
  private static void npmRun(Command command, Path project, String script, String configuration) throws IOException {
    if (!Files.isRegularFile(project.resolve("package.json"))) throw new IllegalArgumentException("项目中不存在 package.json：" + project);
    if (!command.flag("skip-deps-check")) ensureNpmDependencies(project);
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

  /** 查询 npm 服务状态：端口监听且最近一次编译无错为 RUNNING，编译报错为 FAILED。 */
  private static void npmStatus(Path project, String configuration) throws IOException {
    Path stateFile = runState(project, configuration);
    RunState state = RunState.read(stateFile);
    if (state == null || !isProcessAlive(state.pid)) {
      Files.deleteIfExists(stateFile);
      System.out.println(json(mapOf("projectPath", project.toString(), "status", "STOPPED", "running", false)));
      return;
    }
    int port = resolvePort(state);
    boolean portOpen = port > 0 && isPortOpen(port);
    String compileStatus = portOpen ? detectCompileStatus(readLogTail(state.log, LOG_TAIL_LIMIT_BYTES)) : null;
    String status = !portOpen ? "STARTING" : "failed".equals(compileStatus) ? "FAILED" : "RUNNING";
    Map<String, Object> result = state.withPort(port).toMap(status);
    result.put("compileStatus", compileStatus == null ? "unknown" : compileStatus);
    if ("failed".equals(compileStatus)) {
      result.put("compileEvidence", lastCompileEvidence(readLogTail(state.log, LOG_TAIL_LIMIT_BYTES)));
    }
    System.out.println(json(result));
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

  /**
   * 集中列出 simple-ide 启动的所有应用（支持跨项目汇总、状态查询、过滤及一键集中停止）。
   *
   * @param command 命令参数
   * @throws Exception 处理异常
   */
  private static void list(Command command) throws Exception {
    if (command.flag("stop-all")) {
      Map<String, Object> stopResult = stopAllApps(command);
      System.out.println(json(stopResult));
      return;
    }

    Map<String, Object> result = listApps(command);
    if (command.flag("table")) {
      printAppsTable(result);
    } else {
      System.out.println(json(result));
    }
  }

  /**
   * 收集并汇总 simple-ide 启动的应用列表。
   *
   * @param command 命令参数
   * @return 结构化的应用列表结果
   */
  /**
   * 收集并汇总 simple-ide 启动的应用列表与正在使用 simple-ide 的工程目录。
   *
   * @param command 命令参数
   * @return 结构化的应用列表与活跃目录结果
   */
  static Map<String, Object> listApps(Command command) {
    boolean runningOnly = command.flag("running-only");
    boolean clean = command.flag("clean");
    Map<Path, List<Map<String, Object>>> cliOut = new LinkedHashMap<Path, List<Map<String, Object>>>();
    List<Path> projects = resolveProjects(command, cliOut);

    List<Map<String, Object>> allApps = new ArrayList<Map<String, Object>>();
    List<Map<String, Object>> activeProjects = new ArrayList<Map<String, Object>>();
    int runningCount = 0;
    int stoppedCount = 0;

    for (Path project : projects) {
      if (!Files.isDirectory(project)) continue;
      List<Map<String, Object>> apps = collectAssociatedApps(project);
      int projectRunning = 0;
      int projectTotal = 0;

      for (Map<String, Object> app : apps) {
        boolean alive = Boolean.TRUE.equals(app.get("alive"));
        if (clean && !alive) {
          try {
            Files.deleteIfExists(runState(project, (String) app.get("configuration")));
          } catch (Exception ignored) {}
          continue;
        }
        projectTotal++;
        if (alive) {
          runningCount++;
          projectRunning++;
        } else {
          stoppedCount++;
        }
        if (runningOnly && !alive) {
          continue;
        }
        allApps.add(app);
      }

      List<Map<String, Object>> cliList = cliOut.get(project);
      boolean hasCli = cliList != null && !cliList.isEmpty();
      if (projectTotal > 0 || hasCli) {
        Map<String, Object> projMap = new LinkedHashMap<String, Object>();
        projMap.put("projectPath", project.toAbsolutePath().toString());
        projMap.put("runningApps", projectRunning);
        projMap.put("totalApps", projectTotal);
        if (hasCli) {
          projMap.put("cliProcesses", cliList);
        }
        activeProjects.add(projMap);
      }
    }

    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("success", true);
    result.put("total", allApps.size());
    result.put("running", runningCount);
    result.put("stopped", stoppedCount);
    result.put("activeProjects", activeProjects);
    result.put("apps", allApps);
    return result;
  }

  /**
   * 一键停止所有由 simple-ide 启动并处于运行状态的应用。
   *
   * @param command 命令参数
   * @return 停止操作执行结果
   */
  static Map<String, Object> stopAllApps(Command command) {
    List<Path> projects = resolveProjects(command, null);
    List<Map<String, Object>> stoppedList = new ArrayList<Map<String, Object>>();

    for (Path project : projects) {
      if (!Files.isDirectory(project)) continue;
      List<Map<String, Object>> apps = collectAssociatedApps(project);
      for (Map<String, Object> app : apps) {
        if (Boolean.TRUE.equals(app.get("alive"))) {
          String configName = (String) app.get("configuration");
          boolean stopped = stopApplication(project, configName);
          Map<String, Object> item = new LinkedHashMap<String, Object>();
          item.put("configuration", configName);
          item.put("projectPath", project.toAbsolutePath().toString());
          item.put("pid", app.get("pid"));
          item.put("stopped", stopped);
          stoppedList.add(item);
        }
      }
    }

    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("success", true);
    result.put("totalStopped", stoppedList.size());
    result.put("apps", stoppedList);
    return result;
  }

  /**
   * 解析需要扫描的项目列表。若显式指定 --project 则只扫描该项目，否则自动扫描全局活跃系统进程、全局注册表及当前目录。
   *
   * @param command 命令参数
   * @return 解析后的项目路径列表
   */
  static List<Path> resolveProjects(Command command) {
    return resolveProjects(command, null);
  }

  /**
   * 解析需要扫描的项目列表，并可选输出探测到的 CLI 进程映射。
   *
   * @param command 命令参数
   * @param cliOut 可选输出的 CLI 进程列表映射
   * @return 解析后的项目路径列表
   */
  static List<Path> resolveProjects(Command command, Map<Path, List<Map<String, Object>>> cliOut) {
    List<Path> list = new ArrayList<Path>();
    String projOpt = command.option("project", null);
    if (projOpt != null && !projOpt.trim().isEmpty()) {
      list.add(Paths.get(projOpt).toAbsolutePath().normalize());
      return list;
    }

    Set<String> visited = new LinkedHashSet<String>();
    Path current = Paths.get(".").toAbsolutePath().normalize();
    visited.add(current.toString());
    list.add(current);

    // 1. 动态系统进程探测：发现全系统所有正在运行 simple-ide 或托管应用的目录
    Map<Path, List<Map<String, Object>>> discoveredCli = new LinkedHashMap<Path, List<Map<String, Object>>>();
    Set<Path> activeDirs = discoverActiveProjects(discoveredCli);
    if (cliOut != null) {
      cliOut.putAll(discoveredCli);
    }
    for (Path p : activeDirs) {
      if (visited.add(p.toAbsolutePath().normalize().toString())) {
        list.add(p);
        registerProject(p);
      }
    }

    // 2. 读取持久化注册表
    try {
      Path registry = Paths.get(System.getProperty("user.home"), ".simple-ide", "projects.txt");
      if (Files.isRegularFile(registry)) {
        List<String> lines = Files.readAllLines(registry, StandardCharsets.UTF_8);
        for (String line : lines) {
          line = line.trim();
          if (!line.isEmpty() && visited.add(line)) {
            Path p = Paths.get(line).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) {
              list.add(p);
            }
          }
        }
      }
    } catch (Exception ignored) {}

    return list;
  }

  /**
   * 动态探测操作系统中所有正在运行 simple-ide 实例或托管应用的工程目录。
   * 跨平台兼容：Linux 探测 /proc；macOS 通过 ps 与 lsof 探测；Windows 通过 jps 探测与注册表回退。
   * 任何平台探测均做安全兜底，绝不报错或中断。
   *
   * @param cliOut 用于收集发现的正在运行的 simple-ide CLI 进程映射
   * @return 探测到的工程目录集合
   */
  static Set<Path> discoverActiveProjects(Map<Path, List<Map<String, Object>>> cliOut) {
    Set<Path> activeDirs = new LinkedHashSet<Path>();
    try {
      String os = System.getProperty("os.name", "").toLowerCase();
      if (os.contains("linux")) {
        discoverActiveProjectsLinux(cliOut, activeDirs);
      } else if (os.contains("mac") || os.contains("darwin")) {
        discoverActiveProjectsMac(cliOut, activeDirs);
      } else if (os.contains("win")) {
        discoverActiveProjectsWindows(cliOut, activeDirs);
      }
    } catch (Throwable ignored) {}
    return activeDirs;
  }

  /**
   * Linux 环境下通过 /proc 探测活跃目录与 CLI 进程。
   *
   * @param cliOut 用于收集发现的正在运行的 simple-ide CLI 进程映射
   * @param activeDirs 收集的活跃工程目录集合
   */
  private static void discoverActiveProjectsLinux(Map<Path, List<Map<String, Object>>> cliOut, Set<Path> activeDirs) {
    File procDir = new File("/proc");
    if (!procDir.isDirectory()) return;

    File[] pidDirs = procDir.listFiles((dir, name) -> {
      for (int i = 0; i < name.length(); i++) {
        if (!Character.isDigit(name.charAt(i))) return false;
      }
      return true;
    });
    if (pidDirs == null) return;

    for (File pidDir : pidDirs) {
      try {
        long pid = Long.parseLong(pidDir.getName());
        Path cwdLink = pidDir.toPath().resolve("cwd");
        Path cwd = null;
        try {
          cwd = Files.readSymbolicLink(cwdLink);
        } catch (Exception ignored) {
          continue;
        }
        if (cwd == null || !Files.isDirectory(cwd)) continue;

        // 1. 检查 cmdline
        byte[] cmdBytes = null;
        try {
          cmdBytes = Files.readAllBytes(pidDir.toPath().resolve("cmdline"));
        } catch (Exception ignored) {}

        if (cmdBytes != null && cmdBytes.length > 0) {
          String cmdline = new String(cmdBytes, StandardCharsets.UTF_8).replace('\0', ' ');
          if (cmdline.contains("io.simpleide.SimpleIde") || cmdline.contains("bin/simple-ide")) {
            activeDirs.add(cwd);
            if (cliOut != null) {
              recordDiscoveredCli(cliOut, cwd, pid, cmdline);
            }
          }
        }

        // 2. 检查该 cwd 目录下是否有 .simple-ide/runs/*.properties 且首行 PID 匹配
        checkRunsDirectoryMatch(cwd, pid, activeDirs);
      } catch (Throwable ignored) {}
    }
  }

  /**
   * macOS 环境下通过 ps 与 lsof 探测活跃目录与 CLI 进程。
   *
   * @param cliOut 用于收集发现的正在运行的 simple-ide CLI 进程映射
   * @param activeDirs 收集的活跃工程目录集合
   */
  private static void discoverActiveProjectsMac(Map<Path, List<Map<String, Object>>> cliOut, Set<Path> activeDirs) {
    try {
      Process process = new ProcessBuilder("ps", "-eo", "pid,command").redirectErrorStream(true).start();
      String output = new String(readAllBytes(process.getInputStream()), StandardCharsets.UTF_8);
      if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return;
      }

      for (String line : output.split("\n")) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("PID")) continue;
        int firstSpace = line.indexOf(' ');
        if (firstSpace < 0) continue;
        String pidStr = line.substring(0, firstSpace).trim();
        String cmdline = line.substring(firstSpace + 1).trim();
        long pid;
        try {
          pid = Long.parseLong(pidStr);
        } catch (Exception ignored) {
          continue;
        }

        boolean isCli = cmdline.contains("io.simpleide.SimpleIde") || cmdline.contains("bin/simple-ide");
        boolean isManagedTarget = cmdline.contains("java") || cmdline.contains("node") || cmdline.contains("npm");

        if (isCli || isManagedTarget) {
          Path cwd = extractCwdViaLsof(pid);
          if (cwd != null && Files.isDirectory(cwd)) {
            if (isCli) {
              activeDirs.add(cwd);
              if (cliOut != null) {
                recordDiscoveredCli(cliOut, cwd, pid, cmdline);
              }
            }
            if (isManagedTarget) {
              checkRunsDirectoryMatch(cwd, pid, activeDirs);
            }
          }
        }
      }
    } catch (Throwable ignored) {}
  }

  /**
   * 从命令行参数中解析项目路径（例如 --project 选项或 -Duser.dir 属性）。
   *
   * @param cmdline 命令行字符串
   * @return 解析到的项目路径；若无法解析则返回 null
   */
  static Path extractProjectFromCmdline(String cmdline) {
    if (cmdline == null || cmdline.isEmpty()) return null;
    try {
      int idx = cmdline.indexOf("--project");
      if (idx >= 0) {
        String sub = cmdline.substring(idx + "--project".length()).trim();
        if (sub.startsWith("=")) sub = sub.substring(1).trim();
        if (sub.startsWith("\"") || sub.startsWith("'")) {
          char q = sub.charAt(0);
          int endQ = sub.indexOf(q, 1);
          if (endQ > 1) {
            Path p = Paths.get(sub.substring(1, endQ)).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) return p;
          }
        } else {
          String token = sub.split("\\s+")[0];
          if (!token.isEmpty() && !token.startsWith("-")) {
            Path p = Paths.get(token).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) return p;
          }
        }
      }

      int dirIdx = cmdline.indexOf("-Duser.dir=");
      if (dirIdx >= 0) {
        String sub = cmdline.substring(dirIdx + "-Duser.dir=".length()).trim();
        if (sub.startsWith("\"") || sub.startsWith("'")) {
          char q = sub.charAt(0);
          int endQ = sub.indexOf(q, 1);
          if (endQ > 1) {
            Path p = Paths.get(sub.substring(1, endQ)).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) return p;
          }
        } else {
          String token = sub.split("\\s+")[0];
          if (!token.isEmpty()) {
            Path p = Paths.get(token).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) return p;
          }
        }
      }
    } catch (Exception ignored) {}
    return null;
  }

  /**
   * Windows 环境下通过 jps 探测运行中 Java/SimpleIde 进程与托管服务。
   *
   * @param cliOut 用于收集发现的正在运行的 simple-ide CLI 进程映射
   * @param activeDirs 收集的活跃工程目录集合
   */
  private static void discoverActiveProjectsWindows(Map<Path, List<Map<String, Object>>> cliOut, Set<Path> activeDirs) {
    try {
      Process p = new ProcessBuilder("jps", "-v").redirectErrorStream(true).start();
      String out = new String(readAllBytes(p.getInputStream()), StandardCharsets.UTF_8);
      if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
        p.destroyForcibly();
        return;
      }
      for (String line : out.split("\n")) {
        line = line.trim();
        if (line.isEmpty()) continue;
        int space = line.indexOf(' ');
        if (space < 0) continue;
        String pidStr = line.substring(0, space).trim();
        String cmdline = line.substring(space + 1).trim();
        long pid;
        try {
          pid = Long.parseLong(pidStr);
        } catch (Exception ignored) {
          continue;
        }

        boolean isCli = cmdline.contains("SimpleIde") || cmdline.contains("simple-ide");
        Path cwd = extractProjectFromCmdline(cmdline);
        if (cwd == null) {
          try {
            Path registry = Paths.get(System.getProperty("user.home"), ".simple-ide", "projects.txt");
            if (Files.isRegularFile(registry)) {
              for (String regPath : Files.readAllLines(registry, StandardCharsets.UTF_8)) {
                Path rp = Paths.get(regPath.trim()).toAbsolutePath().normalize();
                if (Files.isDirectory(rp)) {
                  checkRunsDirectoryMatch(rp, pid, activeDirs);
                }
              }
            }
          } catch (Exception ignored) {}
        } else {
          activeDirs.add(cwd);
          if (isCli && cliOut != null) {
            recordDiscoveredCli(cliOut, cwd, pid, cmdline);
          }
          checkRunsDirectoryMatch(cwd, pid, activeDirs);
        }
      }
    } catch (Throwable ignored) {}
  }

  /**
   * 通过 lsof 提取指定进程的工作目录 cwd（macOS / Unix 通用）。
   *
   * @param pid 目标进程 PID
   * @return 进程工作目录路径；提取失败返回 null
   */
  private static Path extractCwdViaLsof(long pid) {
    try {
      Process p = new ProcessBuilder("lsof", "-a", "-p", Long.toString(pid), "-d", "cwd", "-Fn").redirectErrorStream(true).start();
      String out = new String(readAllBytes(p.getInputStream()), StandardCharsets.UTF_8);
      if (!p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
        p.destroyForcibly();
        return null;
      }
      for (String line : out.split("\n")) {
        line = line.trim();
        if (line.startsWith("n/")) {
          return Paths.get(line.substring(1)).toAbsolutePath().normalize();
        }
      }
    } catch (Throwable ignored) {}
    return null;
  }

  /**
   * 记录探测到的 simple-ide CLI 进程信息。
   *
   * @param cliOut CLI 进程输出映射
   * @param cwd 工作目录
   * @param pid 进程 PID
   * @param cmdline 命令行字符串
   */
  private static void recordDiscoveredCli(Map<Path, List<Map<String, Object>>> cliOut, Path cwd, long pid, String cmdline) {
    String subCmd = "running";
    String[] tokens = cmdline.split("\\s+");
    for (int i = 0; i < tokens.length; i++) {
      if (tokens[i].endsWith("SimpleIde") && i + 1 < tokens.length) {
        subCmd = tokens[i + 1];
        break;
      }
    }
    List<Map<String, Object>> list = cliOut.get(cwd);
    if (list == null) {
      list = new ArrayList<Map<String, Object>>();
      cliOut.put(cwd, list);
    }
    Map<String, Object> procMap = new LinkedHashMap<String, Object>();
    procMap.put("pid", pid);
    procMap.put("command", subCmd);
    list.add(procMap);
  }

  /**
   * 检查指定目录的 .simple-ide/runs/ 下是否有 PID 匹配的配置并记录为活跃目录。
   *
   * @param cwd 检查的目标工程目录
   * @param pid 运行中进程 PID
   * @param activeDirs 活跃目录输出集合
   */
  private static void checkRunsDirectoryMatch(Path cwd, long pid, Set<Path> activeDirs) {
    try {
      Path runsDir = cwd.resolve(".simple-ide/runs");
      if (Files.isDirectory(runsDir)) {
        File[] propFiles = runsDir.toFile().listFiles((d, name) -> name.endsWith(".properties"));
        if (propFiles != null && propFiles.length > 0) {
          for (File f : propFiles) {
            try {
              List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
              if (!lines.isEmpty() && lines.get(0).trim().equals(Long.toString(pid))) {
                activeDirs.add(cwd);
                break;
              }
            } catch (Exception ignored) {}
          }
        }
      }
    } catch (Throwable ignored) {}
  }

  /**
   * 将项目路径持久化注册到用户主目录全局注册表（~/.simple-ide/projects.txt）。
   *
   * @param project 目标项目路径
   */
  static void registerProject(Path project) {
    if (project == null) return;
    try {
      Path globalDir = Paths.get(System.getProperty("user.home"), ".simple-ide");
      Files.createDirectories(globalDir);
      Path registry = globalDir.resolve("projects.txt");
      String projectPath = project.toAbsolutePath().normalize().toString();
      Set<String> projects = new LinkedHashSet<String>();
      if (Files.isRegularFile(registry)) {
        List<String> lines = Files.readAllLines(registry, StandardCharsets.UTF_8);
        for (String l : lines) {
          if (!l.trim().isEmpty()) projects.add(l.trim());
        }
      }
      if (projects.add(projectPath)) {
        List<String> out = new ArrayList<String>(projects);
        Files.write(registry, out, StandardCharsets.UTF_8);
      }
    } catch (Exception ignored) {}
  }

  /**
   * 以整齐的终端表格格式输出应用管控清单与活跃工程目录。
   *
   * @param result 应用列表数据
   */
  static void printAppsTable(Map<String, Object> result) {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> activeProjects = (List<Map<String, Object>>) result.get("activeProjects");
    if (activeProjects != null && !activeProjects.isEmpty()) {
      System.out.println("正在启动或使用 simple-ide 的目录 (Active Directories):");
      for (Map<String, Object> p : activeProjects) {
        StringBuilder sb = new StringBuilder("  • ").append(p.get("projectPath"));
        int running = p.get("runningApps") instanceof Number ? ((Number) p.get("runningApps")).intValue() : 0;
        int total = p.get("totalApps") instanceof Number ? ((Number) p.get("totalApps")).intValue() : 0;
        sb.append(" [运行中应用: ").append(running).append("/").append(total);
        if (p.containsKey("cliProcesses")) {
          @SuppressWarnings("unchecked")
          List<Map<String, Object>> clis = (List<Map<String, Object>>) p.get("cliProcesses");
          if (clis != null && !clis.isEmpty()) {
            sb.append(" | CLI: ");
            for (int i = 0; i < clis.size(); i++) {
              if (i > 0) sb.append(", ");
              Map<String, Object> c = clis.get(i);
              sb.append(c.get("command")).append(" (PID ").append(c.get("pid")).append(")");
            }
          }
        }
        sb.append("]");
        System.out.println(sb.toString());
      }
      System.out.println();
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> apps = (List<Map<String, Object>>) result.get("apps");
    if (apps == null || apps.isEmpty()) {
      System.out.println("暂无 simple-ide 启动的应用。");
      return;
    }

    System.out.printf("%-20s %-8s %-8s %-10s %-8s %-8s %s%n", "CONFIGURATION", "TYPE", "PID", "STATUS", "PORT", "DEBUG", "PROJECT");
    System.out.println("----------------------------------------------------------------------------------------------------");
    for (Map<String, Object> app : apps) {
      String cfg = String.valueOf(app.get("configuration"));
      String type = String.valueOf(app.get("type"));
      String pid = String.valueOf(app.get("pid"));
      String st = String.valueOf(app.get("status"));
      int port = app.get("port") instanceof Number ? ((Number) app.get("port")).intValue() : -1;
      int dbg = app.get("debugPort") instanceof Number ? ((Number) app.get("debugPort")).intValue() : -1;
      String portStr = port > 0 ? String.valueOf(port) : "-";
      String dbgStr = dbg > 0 ? String.valueOf(dbg) : "-";
      String prj = String.valueOf(app.get("projectPath"));
      System.out.printf("%-20s %-8s %-8s %-10s %-8s %-8s %s%n", cfg, type, pid, st, portStr, dbgStr, prj);
    }
  }

  // ==================== 工具方法 ====================

  /** 获取 Process PID（兼容 JDK 9+ 与 JDK 8）。 */
  static long getPid(Process process) {
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

  /** 检测进程是否存活（兼容 Linux、macOS 与 Windows）。 */
  private static boolean isProcessAlive(long pid) {
    if (pid <= 0) return false;
    try {
      String os = System.getProperty("os.name", "").toLowerCase();
      if (os.contains("win")) {
        Process p = new ProcessBuilder("tasklist", "/fi", "PID eq " + pid).redirectErrorStream(true).start();
        String output = new String(readAllBytes(p.getInputStream()), StandardCharsets.UTF_8);
        return p.waitFor() == 0 && output.contains(Long.toString(pid));
      } else {
        Process p = new ProcessBuilder("kill", "-0", Long.toString(pid)).redirectErrorStream(true).start();
        return p.waitFor() == 0;
      }
    }
    catch (Throwable e) { return false; }
  }

  /** 向进程组或进程发送终止信号（兼容 Linux、macOS 与 Windows）。 */
  private static void signalProcessGroup(long pid, String signal) throws IOException {
    if (pid <= 0) return;
    try {
      String os = System.getProperty("os.name", "").toLowerCase();
      if (os.contains("win")) {
        new ProcessBuilder("taskkill", "/F", "/T", "/PID", Long.toString(pid)).start().waitFor();
      } else {
        try {
          new ProcessBuilder("/bin/kill", "-" + signal, "--", "-" + pid).start().waitFor();
        } catch (Throwable fallback) {
          new ProcessBuilder("kill", "-" + signal, Long.toString(pid)).start().waitFor();
        }
      }
    }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("终止进程时被中断", e); }
    catch (Throwable ignored) {}
  }

  /** 判断命令行是否请求帮助。 */
  private static boolean isHelpRequest(String[] arguments) {
    return arguments.length == 0 || "help".equals(arguments[0]) || "--help".equals(arguments[0]) || "-h".equals(arguments[0])
      || (arguments.length > 1 && ("--help".equals(arguments[1]) || "-h".equals(arguments[1])));
  }

  /** 返回帮助文本。 */
  static String helpText(String command) {
    if (command == null || "help".equals(command) || "--help".equals(command) || "-h".equals(command))
      return "用法: simple-ide <command> [options]\n\n命令:\n  build   编译项目模块（默认内置 JPS 增量引擎，秒级编译）\n  run     启动 Application 配置（默认纯运行模式，对齐 IDEA Run）\n  debug   调试启动 Application 配置（自动开启 JDWP 调试端口，对齐 IDEA Debug）\n  reload  对 run --hot-reload 启动的 Spring Boot 应用触发一次热更新\n  test    运行单元测试（默认本地 JPS 编译与 JUnit 平台执行）\n  status  查询应用状态\n  build-status  查询最近一次构建的真实阶段/耗时/日志/失败原因\n  version 打印工具版本、jps-cli 摘要与缓存格式版本\n  list    列出 simple-ide 启动的所有应用并集中管控\n  logs    查询应用日志尾部\n  stop    停止应用进程\n  report  生成并在浏览器中打开 HTML 状态报告\n\n环境变量:\n  IJ_MCP_SERVER_PORT  IDEA MCP Server 端口（默认 64342）\n\n使用 `simple-ide <command> --help` 查看子命令参数。\n";
    if ("list".equals(command)) return "用法: simple-ide list [--project <path>] [--running-only] [--clean] [--stop-all] [--table]\n\n列出 simple-ide 启动的所有应用并集中管控（支持跨项目汇总、展示 PID/端口/状态/日志；--running-only 仅看存活应用；--stop-all 一键集中停止所有应用；--clean 清理失效状态记录；--table 以表格格式输出）。\n";
    if ("build".equals(command)) return "用法: simple-ide build --project <path> [--module <artifactId>] [--rebuild | --auto-rebuild] [--no-auto-restart] [--lock-timeout <sec>] [--no-lock] [--engine <jps|idea-mcp>] [--timeout <sec>]\n\n编译项目。默认使用内置 JPS 增量编译引擎（秒级增量编译，不依赖 IDEA 运行）。默认启用本地构建排队锁，避免并发编译资源竞争。\n\n--rebuild 直接从清理共享输出开始重建；若存在运行中的 Application 会拒绝清理并返回 rebuildRequired/rebuildReason/runningConfigurations。\n--auto-rebuild 先执行增量编译，仅当命中明确的缓存/历史 class 损坏特征时才自动全量重建一次；真实源码错误或 Lombok builder 缺失不会触发重建。\n--module 限定构建模块（自动包含其依赖模块）；JPS 不支持 --file 文件粒度作用域，非空时会显式拒绝而非静默忽略。\n存在运行中的应用且检测到待编译源文件时，默认先停止应用 → 用共享输出编译 → 成功后按原参数重启（避免改动只写进隔离目录、运行时仍是旧 class）；无待编译文件则不动应用。--no-auto-restart 关闭该行为。\n--isolate-cache 强制使用隔离缓存目录构建（默认在「存在运行中应用且未授权清理」时已自动隔离），使 JPS 内部的删除重建只影响隔离副本；--no-isolate-cache 关闭该保护。\n退出码：编译失败=1，增量存储损坏需显式重建=3，依赖解析失败=4，构建锁被遗留进程占用=5。\n超时/取消会记录后代进程身份后回收整棵子进程树；无法枚举进程表或仍无法确认退出时返回 PROCESS_TREE_NOT_TERMINATED 并列出存活 PID（不承诺无法保证的强杀恢复）。\n构建过程实时向 stderr 输出进度与心跳，stdout 仅输出稳定单行 JSON（含 success/errorCode/phase/diagnostics/buildId/pid/logFile）。\n设置 SIMPLE_IDE_HOTSWAP=1 时，构建成功后额外创建 .simple-ide/hotswap 工作目录并校验内置 agent jar，供后续 reload 使用方法体热替换。\n";
    if ("run".equals(command)) return "用法: simple-ide run --project <path> [--configuration <name>] [--random-port | --port <number>] [--debug] [--debug-port <port>] [--java <path>] [--engine <local|idea-mcp>] [--skip-build] [--hot-reload] [--hot-reload-include <regex>]... [--vm-arg <arg>]...\n\n启动 Application。默认纯运行模式（读取 runConfigurations 配置并注入 JPS 编译依赖 Classpath，对齐 IDEA Run 极致性能）。可选 --debug 或 --debug-port 开启 JDWP 远程调试。--random-port 自动选择空闲端口并传入 --server.port；两者都不指定时由 status/list 从进程监听端口自动探测实际端口。\n--hot-reload 仅支持 Spring Boot + --engine local：把内置 spring-boot-devtools-2.7.18-simpleide.jar 注入应用 classpath，并启用 trigger-file 热更新；之后用 simple-ide reload 触发。\n--hot-reload-include 把匹配的依赖 jar 加入 DevTools restart classpath（可重复，值为 URL 正则）；用于应用输出目录覆盖了依赖类、且该覆盖类引用应用代码的场景。\n--vm-arg 追加 JVM 启动参数（可重复，原样透传并插在 -cp 之前），用于启动期 profiling 等：\n  simple-ide run --project <path> --vm-arg '-agentpath:/path/libasyncProfiler.so=start,event=wall,interval=1ms,timeout=120,file=/tmp/boot.html,threads,simple'\n设置 SIMPLE_IDE_HOTSWAP=1 时，注入内置 simple-ide-hotswap-agent.jar，启用方法体原地热替换；该模式与 --hot-reload 互斥。\n";
    if ("reload".equals(command)) return "用法: simple-ide reload --project <path> [--configuration <name>] [--timeout <sec>]\n\n默认对 run --hot-reload 启动的 Spring Boot 应用执行 DevTools 热更新：JPS 增量编译到共享 target/classes，然后更新 DevTools trigger 文件。\n设置 SIMPLE_IDE_HOTSWAP=1 时改为方法体热替换：JPS 增量编译后把变更 class 交给内置 agent 执行 redefineClasses，不重启 Spring context；加字段/方法/改签名返回 SCHEMA_CHANGE_REQUIRES_RESTART。\n返回 success/configuration/compiled/redefined/skipped/classes；编译失败时返回 BUILD_FAILED 与结构化 build 信息，不触发重启。\n注意：该命令会直接写运行中应用的 target/classes（live write），这是显式取舍。\n";
    if ("debug".equals(command)) return "用法: simple-ide debug --project <path> [--configuration <name>] [--random-port | --port <number>] [--debug-port <port>] [--java <path>] [--engine <local|idea-mcp>] [--skip-build] [--vm-arg <arg>]...\n\n调试启动 Application（对齐 IDEA Debug 模式）。默认开启 JDWP 远程调试（默认端口 5005，可通过 --debug-port 自定义）。--random-port 自动选择空闲端口并传入 --server.port。\n--vm-arg 追加 JVM 启动参数（可重复，原样透传并插在 -cp 之前），用于启动期 profiling 等：\n  simple-ide run --project <path> --vm-arg '-agentpath:/path/libasyncProfiler.so=start,event=wall,interval=1ms,timeout=120,file=/tmp/boot.html,threads,simple'\n";
    if ("test".equals(command)) return "用法: simple-ide test --project <path> --class <FQN> [--method <name>] [--module <artifactId>] [--engine <jps|idea-mcp>] [--skip-build] [--timeout <sec>]\n\n运行单元测试。默认通过内置 JPS 增量编译并在本地 JUnit 平台执行，秒级反馈，不依赖 IDEA 运行。\n未指定 --module 时按测试源码所在模块推断作用域；多模块且无法唯一确定时显式报错。\n结果 JSON 通过真实解析判定 success，并区分 failed/skipped/aborted/notExecuted/containersFailed。\n";
    if ("status".equals(command)) return "用法: simple-ide status --project <path> [--configuration <name>] [--engine <local|idea-mcp>]\n\n默认 --engine local 查询本地运行状态；--engine idea-mcp 查询已运行的 IDEA MCP 状态。\n";
    if ("build-status".equals(command)) return "用法: simple-ide build-status [--project <path>] [--build <buildId|latest>] [--tail <lines>]\n\n查询构建真实状态：phase、state、pid、startedAt、durationMs、logFile、exitCode、errorCode。\n不提供百分比或 ETA；长构建期间进度与心跳输出在 stderr。\n";
    if ("version".equals(command)) return "用法: simple-ide version\n\n打印工具版本、缓存格式版本与内置 jps-cli jar 的路径/大小/SHA-256，便于确认分发构建与安装版本一致。\n";
    if ("logs".equals(command)) return "用法: simple-ide logs --project <path> --configuration <name> [--tail <lines>]\n";
    if ("stop".equals(command)) return "用法: simple-ide stop --project <path> [--configuration <name>] [--engine <local|idea-mcp>]\n\n停止指定运行配置的应用进程。默认 --engine local 仅停止本地进程。\n";
    if ("report".equals(command)) return "用法: simple-ide report [--project <path>] [--port <number>] [--daemon] [--static] [--no-open]\n\n打开项目管理控制台（默认动态本地 HTTP 工作台，支持 3 秒实时轮询、实时日志流、一键停止应用；--static 导出静态单页 HTML；--daemon 让 HTTP 服务后台常驻）。\n";
    if ("npm".equals(command)) return "用法: simple-ide npm <run|status|logs|stop> --project <path> --script <name> [options]\n";
    if ("npm run".equals(command)) return "用法: simple-ide npm run --project <path> --script <name> [--random-port | --port <number>] [--port-argument <flag>] [--npm <path>] [--env KEY=VALUE] [--skip-deps-check]\n\n启动前校验 package.json 声明的依赖是否已安装，缺失时快速失败并提示执行 npm install；--skip-deps-check 可跳过该校验。\n";
    if ("npm status".equals(command)) return "用法: simple-ide npm status --project <path> --script <name>\n\nstatus 会结合端口监听与最近一次编译结果：编译报错时返回 FAILED（含 compileStatus / compileEvidence），编译成功为 RUNNING，端口未监听为 STARTING。\n";
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

  /** 读取日志文件末尾最多 maxBytes 字节的文本，用于判定最近一次编译结果。 */
  static String readLogTail(Path log, int maxBytes) throws IOException {
    if (log == null || !Files.isRegularFile(log)) return "";
    byte[] bytes = Files.readAllBytes(log);
    int start = Math.max(0, bytes.length - maxBytes);
    // 跳过可能被截断的 UTF-8 续字节，避免首字符解码异常。
    while (start < bytes.length && (bytes[start] & 0xC0) == 0x80) start++;
    return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
  }

  /**
   * 从 npm 服务日志文本中判定最近一次编译结果。
   *
   * @return "failed" 表示最近一次编译报错，"ok" 表示编译成功，null 表示日志中尚无编译结果。
   */
  static String detectCompileStatus(String logText) {
    if (logText == null || logText.isEmpty()) return null;
    String status = null;
    for (String line : logText.split("\n")) {
      if (line.matches("(?i).*compiled with\\s+\\d+\\s+error.*")) status = "failed";
      else if (line.matches("(?i).*compiled successfully.*")) status = "ok";
      else if (line.matches("(?i)^\\s*(ERROR in |Module not found|Module build failed).*")) status = "failed";
    }
    return status;
  }

  /** 返回最近一次编译失败的可读证据行（优先 ERROR 明细，其次编译汇总行）。 */
  static String lastCompileEvidence(String logText) {
    if (logText == null || logText.isEmpty()) return null;
    String errorLine = null;
    String summaryLine = null;
    for (String line : logText.split("\n")) {
      if (line.matches("(?i)^\\s*ERROR in .*")) errorLine = line.trim();
      if (line.matches("(?i).*compiled (successfully|with\\s+\\d+\\s+error).*")) summaryLine = line.trim();
    }
    return errorLine != null ? errorLine : summaryLine;
  }

  /** 校验 package.json 声明的直接依赖均已安装，缺失时给出可操作的错误信息。 */
  static void ensureNpmDependencies(Path project) throws IOException {
    Path packageJson = project.resolve("package.json");
    if (!Files.isRegularFile(packageJson)) return;
    String text = new String(Files.readAllBytes(packageJson), StandardCharsets.UTF_8);
    List<String> missing = missingNpmDependencies(project, text);
    if (missing.isEmpty()) return;
    List<String> preview = missing.size() > 8 ? missing.subList(0, 8) : missing;
    String suffix = missing.size() > preview.size() ? " 等" : "";
    throw new IllegalStateException("npm 依赖未安装或与 package.json 不同步，缺少 " + missing.size() + " 个包："
      + String.join(", ", preview) + suffix + "；请先在 " + project + " 执行 `npm install`，或使用 --skip-deps-check 跳过校验");
  }

  /** 返回 package.json 声明但 node_modules 中缺失的直接依赖包名。 */
  static List<String> missingNpmDependencies(Path project, String packageJson) {
    List<String> missing = new ArrayList<String>();
    Path modules = project.resolve("node_modules");
    for (String name : declaredDependencies(packageJson)) {
      if (!Files.isDirectory(modules.resolve(name))) missing.add(name);
    }
    return missing;
  }

  /** 从 package.json 文本中解析 dependencies 与 devDependencies 的直接依赖包名。 */
  static List<String> declaredDependencies(String packageJson) {
    List<String> names = new ArrayList<String>();
    if (packageJson == null) return names;
    for (String section : new String[]{"dependencies", "devDependencies"}) {
      String body = jsonObjectBody(packageJson, section);
      if (body != null) names.addAll(jsonObjectKeys(body));
    }
    return names;
  }

  /** 提取 JSON 中指定键对应的对象体（不含外层花括号）；未命中返回 null。 */
  static String jsonObjectBody(String text, String key) {
    String needle = "\"" + key + "\"";
    int index = text.indexOf(needle);
    if (index < 0) return null;
    int open = text.indexOf('{', index + needle.length());
    if (open < 0) return null;
    int depth = 0;
    for (int i = open; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '"') { i = skipJsonString(text, i); continue; }
      if (c == '{') depth++;
      else if (c == '}') { depth--; if (depth == 0) return text.substring(open + 1, i); }
    }
    return null;
  }

  /** 提取对象体中第一层的键名（值中的嵌套对象与字符串不计入）。 */
  static List<String> jsonObjectKeys(String body) {
    List<String> keys = new ArrayList<String>();
    int depth = 0;
    for (int i = 0; i < body.length(); i++) {
      char c = body.charAt(i);
      if (c == '"') {
        int end = skipJsonString(body, i);
        if (depth == 0) {
          String token = body.substring(i + 1, end);
          int cursor = end + 1;
          while (cursor < body.length() && Character.isWhitespace(body.charAt(cursor))) cursor++;
          if (cursor < body.length() && body.charAt(cursor) == ':') keys.add(token);
        }
        i = end;
      }
      else if (c == '{' || c == '[') depth++;
      else if (c == '}' || c == ']') depth--;
    }
    return keys;
  }

  /** 返回 JSON 字符串结束引号的索引，兼容反斜杠转义。 */
  static int skipJsonString(String text, int startQuote) {
    for (int i = startQuote + 1; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\\') { i++; continue; }
      if (c == '"') return i;
    }
    return text.length() - 1;
  }

  /** 解析固定端口或分配随机端口。 */
  private static int configuredPort(Command command) throws IOException {
    if (command.flag("random-port")) return findAvailablePort();
    String value = command.option("port", null);
    return value == null ? -1 : Integer.parseInt(value);
  }

  /**
   * 判断参数列表中是否已包含 JDWP 调试配置。
   *
   * @param params 命令行或 VM 参数列表
   * @return 若已包含则返回 true，否则 false
   */
  static boolean hasJdwpParameter(List<String> params) {
    if (params == null) return false;
    for (String param : params) {
      if (param.startsWith("-agentlib:jdwp") || param.startsWith("-Xrunjdwp")) {
        return true;
      }
    }
    return false;
  }

  /**
   * 从参数列表中提取已配置的 JDWP 调试端口。
   *
   * @param params 命令行或 VM 参数列表
   * @return 提取到的端口号；若无 JDWP 参数返回 0；若包含 JDWP 但无法提取端口则返回 -1
   */
  static int extractJdwpPort(List<String> params) {
    if (params == null) return 0;
    Pattern pattern = Pattern.compile("address=(?:[^:,]+:)?(\\d+)");
    for (String param : params) {
      if (param.startsWith("-agentlib:jdwp") || param.startsWith("-Xrunjdwp")) {
        Matcher matcher = pattern.matcher(param);
        if (matcher.find()) {
          try {
            return Integer.parseInt(matcher.group(1));
          } catch (NumberFormatException ignored) {}
        }
        return -1;
      }
    }
    return 0;
  }

  /**
   * 判断端口是否可用（未被占用且可绑定）。
   *
   * @param port 目标端口
   * @return 若端口空闲可用返回 true，被占用或无效返回 false
   */
  static boolean isPortAvailable(int port) {
    if (port <= 0 || port > 65535) return false;
    try (ServerSocket ss = new ServerSocket(port)) {
      ss.setReuseAddress(true);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * 解析应用程序启动时的 JDWP 调试端口。
   * 仅在 debug 子命令、或显式携带 --debug / --debug-port 时开启调试端口；
   * 常规 run 命令默认不启用调试端口（对齐 IDEA Run 纯运行模式），以获得最佳启动性能。
   *
   * @param command 命令参数
   * @param vmParams 原始 VM 参数
   * @return 最终调试端口；若禁用或未配置有效端口则返回 -1
   */
  static int resolveDebugPort(Command command, List<String> vmParams) {
    if (command.flag("no-debug")) {
      return -1;
    }
    boolean isDebugCommand = "debug".equals(command.name);
    boolean hasDebugFlag = command.flag("debug");
    String explicit = command.option("debug-port", null);

    // 常规 run 且未显式指定 debug 时，默认不开启调试代理
    if (!isDebugCommand && !hasDebugFlag && explicit == null) {
      if (hasJdwpParameter(vmParams)) {
        int existing = extractJdwpPort(vmParams);
        return existing > 0 ? existing : -1;
      }
      return -1;
    }

    if (explicit != null) {
      return Integer.parseInt(explicit);
    }
    if (hasJdwpParameter(vmParams)) {
      int existing = extractJdwpPort(vmParams);
      return existing > 0 ? existing : -1;
    }
    if (isPortAvailable(DEFAULT_DEBUG_PORT)) {
      return DEFAULT_DEBUG_PORT;
    }
    try {
      return findAvailablePort();
    } catch (IOException ignored) {
      return DEFAULT_DEBUG_PORT;
    }
  }

  /**
   * 构建 Java 进程启动参数列表，注入兼容 Java 8 及后续版本的 JDWP 端口写法。
   *
   * @param javaCmd java 可执行文件路径
   * @param vmParams 原始 VM 参数
   * @param debugPort 调试端口（<= 0 表示不启用）
   * @param classpath 依赖类路径
   * @param mainClass 主类全限定名
   * @param serverPort 业务服务端口（<= 0 表示不追加 --server.port）
   * @param programParams 应用程序参数
   * @return 完整的命令行参数列表
   */
  static List<String> buildJavaCommandLine(
      String javaCmd,
      List<String> vmParams,
      int debugPort,
      String classpath,
      String mainClass,
      int serverPort,
      List<String> programParams) {
    List<String> command = new ArrayList<String>();
    boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
    if (!isWindows) {
      command.add("setsid");
    }
    command.add(javaCmd != null && !javaCmd.isEmpty() ? javaCmd : "java");

    boolean hasExistingJdwp = hasJdwpParameter(vmParams);
    if (vmParams != null) {
      command.addAll(vmParams);
    }

    if (debugPort > 0 && !hasExistingJdwp) {
      command.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=" + debugPort);
    }

    if (classpath != null && !classpath.isEmpty()) {
      command.add("-cp");
      command.add(classpath);
    }

    if (mainClass != null && !mainClass.isEmpty()) {
      command.add(mainClass);
    }
    if (serverPort > 0) {
      command.add("--server.port=" + serverPort);
    }
    if (programParams != null) {
      command.addAll(programParams);
    }
    return command;
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

  /**
   * 解析运行状态对应的实际端口：显式指定过端口时直接复用；未指定且进程存活时，
   * 从进程监听套接字自动探测主端口，使无 --port 启动的应用也能显示真实端口与 RUNNING。
   *
   * @param state 运行状态
   * @return 实际端口；无法判定时返回 -1
   */
  static int resolvePort(RunState state) {
    if (state == null) return -1;
    if (state.port > 0) return state.port;
    if (state.pid <= 0 || !isProcessAlive(state.pid)) return -1;
    return PortDetector.detect(state.pid, state.debugPort, state.log);
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
      if (arguments.length == 0) throw new IllegalArgumentException("用法：simple-ide <build|run|reload|status|stop> --project <目录>");
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

    Path optionalProject() {
      String p = option("project", ".");
      return Paths.get(p).toAbsolutePath().normalize();
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
    final int debugPort;
    final String projectPath;
    final List<String> command;

    RunState(long pid, Path log, int port, int debugPort, String projectPath, List<String> command) {
      this.pid = pid;
      this.log = log;
      this.port = port;
      this.debugPort = debugPort;
      this.projectPath = projectPath;
      this.command = command;
    }

    RunState(long pid, Path log, int port, String projectPath, List<String> command) {
      this(pid, log, port, -1, projectPath, command);
    }

    /**
     * 返回端口被替换后的状态副本，用于把自动探测到的端口反映到输出中。
     *
     * @param newPort 实际端口
     * @return 新状态对象（端口未变化时返回自身）
     */
    RunState withPort(int newPort) {
      if (newPort == port) return this;
      return new RunState(pid, log, newPort, debugPort, projectPath, command);
    }

    static RunState read(Path file) throws IOException {
      if (!Files.isRegularFile(file)) return null;
      List<String> values = Files.readAllLines(file, StandardCharsets.UTF_8);
      if (values.size() >= 5 && values.get(2).matches("-?\\d+") && values.get(3).matches("-?\\d+")) {
        return new RunState(Long.parseLong(values.get(0)), Paths.get(values.get(1)), Integer.parseInt(values.get(2)), Integer.parseInt(values.get(3)), values.get(4), values.subList(5, values.size()));
      }
      if (values.size() >= 4 && values.get(2).matches("-?\\d+")) {
        List<String> cmd = values.subList(4, values.size());
        int debugPort = extractJdwpPort(cmd);
        return new RunState(Long.parseLong(values.get(0)), Paths.get(values.get(1)), Integer.parseInt(values.get(2)), debugPort, values.get(3), cmd);
      }
      return new RunState(Long.parseLong(values.get(0)), Paths.get(values.get(1)), -1, -1, "", values.subList(2, values.size()));
    }

    /**
     * 将当前进程运行状态持久化落盘，并自动将项目路径登记到全局注册表。
     *
     * @param file 状态文件保存路径
     * @throws IOException I/O 写入异常
     */
    void write(Path file) throws IOException {
      Files.createDirectories(file.getParent());
      List<String> values = new ArrayList<String>();
      values.add(Long.toString(pid));
      values.add(log.toString());
      values.add(Integer.toString(port));
      values.add(Integer.toString(debugPort));
      values.add(projectPath);
      values.addAll(command);
      Files.write(file, values, StandardCharsets.UTF_8);
      if (projectPath != null && !projectPath.trim().isEmpty()) {
        try {
          registerProject(Paths.get(projectPath));
        } catch (Exception ignored) {}
      }
    }

    boolean alive() {
      return isProcessAlive(pid);
    }

    Map<String, Object> toMap(String status) {
      Map<String, Object> value = new LinkedHashMap<String, Object>();
      value.put("projectPath", projectPath);
      value.put("pid", pid);
      value.put("port", port);
      if (debugPort > 0) {
        value.put("debugPort", debugPort);
      }
      value.put("status", status);
      value.put("running", true);
      value.put("log", log.toString());
      // 命令只输出折叠摘要：完整启动命令含超长 classpath，直接内嵌会让 status/list 输出刷屏
      value.put("command", commandSummary());
      value.put("commandNote", "已折叠 -cp 的 classpath；完整启动命令见 .simple-ide/runs 下同名 .properties");
      return value;
    }

    /**
     * 生成启动命令摘要：把 -cp/-classpath 后的超长 classpath 折叠为「条目数 + 字符数」。
     *
     * 完整启动命令仍完整保存在 .simple-ide/runs/&lt;配置&gt;.properties，排障时直接读该文件即可。
     *
     * @return 折叠后的命令摘要；无命令时返回空串
     */
    String commandSummary() {
      if (command == null || command.isEmpty()) return "";
      StringBuilder summary = new StringBuilder();
      for (int i = 0; i < command.size(); i++) {
        String arg = command.get(i);
        if (summary.length() > 0) summary.append(' ');
        if ("-cp".equals(arg) || "-classpath".equals(arg) || "--class-path".equals(arg)) {
          String classpath = (i + 1 < command.size()) ? command.get(i + 1) : "";
          summary.append(arg).append(" <classpath: ").append(classpathEntryCount(classpath))
            .append(" 项, ").append(classpath.length()).append(" 字符>");
          i++;
          continue;
        }
        summary.append(arg);
      }
      return summary.toString();
    }

    /**
     * 统计 classpath 字符串包含的条目数。
     *
     * @param classpath classpath 字符串（Linux 用 ':'、Windows 用 ';' 分隔）
     * @return 条目数；空串返回 0
     */
    static int classpathEntryCount(String classpath) {
      if (classpath == null || classpath.isEmpty()) return 0;
      String separator = classpath.indexOf(';') >= 0 && classpath.indexOf(':') < 0 ? ";" : ":";
      return classpath.split(java.util.regex.Pattern.quote(separator), -1).length;
    }
  }

  /** 表示 IDEA 构建执行的成功状态与诊断结果。 */
  static final class BuildStatus {
    final boolean success;
    String jsonResult;
    /** 是否为受保护隔离构建（编译输出未发布到共享 target）。 */
    boolean isolatedBuild;
    /** 隔离构建的主输出目录映射（模块名 → classes）。 */
    Map<String, String> isolatedClasses;
    /** 隔离构建的测试输出目录映射（模块名 → test-classes）。 */
    Map<String, String> isolatedTestClasses;

    BuildStatus(boolean success, String jsonResult) {
      this.success = success;
      this.jsonResult = jsonResult;
    }
  }

  /** 表示测试执行的完成状态与结果。 */
  static final class TestStatus {
    final boolean executed;
    final boolean success;
    final String jsonResult;

    TestStatus(boolean executed, boolean success, String jsonResult) {
      this.executed = executed;
      this.success = success;
      this.jsonResult = jsonResult;
    }
  }
}

