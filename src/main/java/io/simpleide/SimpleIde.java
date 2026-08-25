package io.simpleide;

import org.jetbrains.jps.api.BuildType;
import org.jetbrains.jps.api.CanceledStatus;
import org.jetbrains.jps.api.GlobalOptions;
import org.jetbrains.jps.api.CmdlineRemoteProto.Message.ControllerMessage.ParametersMessage.TargetTypeBuildScope;
import org.jetbrains.jps.builders.java.JavaModuleBuildTargetType;
import org.jetbrains.jps.cmdline.BuildRunner;
import org.jetbrains.jps.cmdline.JpsModelLoaderImpl;
import org.jetbrains.jps.cmdline.ProjectDescriptor;
import org.jetbrains.jps.incremental.MessageHandler;
import org.jetbrains.jps.incremental.fs.BuildFSState;
import org.jetbrains.jps.incremental.messages.BuildMessage;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** 提供基于开源 JPS 的最小 Headless IDE 命令行。 */
public final class SimpleIde {
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
      switch (command.name) {
        case "build" -> build(command);
        case "run" -> run(command);
        case "test" -> test(command);
        case "status" -> status(command);
        case "logs" -> logs(command);
        case "stop" -> stop(command);
        default -> throw new IllegalArgumentException("不支持的命令：" + command.name);
      }
    }
    catch (Exception error) {
      System.out.println(json(Map.of("success", false, "error", error.getMessage())));
      System.exit(1);
    }
  }

  /** 执行 npm 开发服务的启动、状态、日志或停止操作。 */
  private static void npm(Command command) throws IOException {
    Path project = command.project();
    String script = command.required("script");
    String configuration = "npm-" + script.replaceAll("[^A-Za-z0-9_.-]", "_");
    switch (command.name) {
      case "run" -> npmRun(command, project, script, configuration);
      case "status" -> npmStatus(project, configuration);
      case "logs" -> npmLogs(command, project, configuration);
      case "stop" -> npmStop(project, configuration);
      default -> throw new IllegalArgumentException("npm 不支持的命令：" + command.name);
    }
  }

  /** 启动 npm script，并将可选端口参数传递给底层开发服务器。 */
  private static void npmRun(Command command, Path project, String script, String configuration) throws IOException {
    if (!Files.isRegularFile(project.resolve("package.json"))) throw new IllegalArgumentException("项目中不存在 package.json：" + project);
    Path stateFile = runState(project, configuration);
    RunState current = RunState.read(stateFile);
    if (current != null && current.alive()) throw new IllegalStateException("npm 服务已在运行，PID=" + current.pid);
    int port = configuredPort(command);
    List<String> processCommand = new ArrayList<>(List.of("setsid", command.option("npm", "npm"), "run", script));
    if (port > 0) processCommand.addAll(List.of("--", command.option("port-argument", "--port"), Integer.toString(port)));
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
    RunState state = new RunState(process.pid(), log, port, project.toString(), processCommand);
    state.write(stateFile);
    System.out.println(json(state.toMap(port > 0 ? "STARTING" : "RUNNING")));
  }

  /** 查询 npm 服务状态，清理已退出进程的陈旧状态文件。 */
  private static void npmStatus(Path project, String configuration) throws IOException {
    Path stateFile = runState(project, configuration);
    RunState state = RunState.read(stateFile);
    if (state == null || !state.alive()) {
      Files.deleteIfExists(stateFile);
      System.out.println(json(Map.of("projectPath", project.toString(), "status", "STOPPED", "running", false)));
      return;
    }
    System.out.println(json(state.toMap(state.port > 0 && isPortOpen(state.port) ? "RUNNING" : "STARTING")));
  }

  /** 返回 npm 服务日志的最新行。 */
  private static void npmLogs(Command command, Path project, String configuration) throws IOException {
    int lines = Integer.parseInt(command.option("tail", "200"));
    if (lines < 1) throw new IllegalArgumentException("--tail 必须大于 0");
    Path log = project.resolve(".simple-ide/runs/" + configuration + ".log");
    System.out.println(json(Map.of("projectPath", project.toString(), "configuration", configuration, "logPath", log.toString(), "lines", tail(log, lines))));
  }

  /** 停止 npm 服务并清理其状态文件。 */
  private static void npmStop(Path project, String configuration) throws IOException {
    Path stateFile = runState(project, configuration);
    RunState state = RunState.read(stateFile);
    if (state == null || !state.alive()) {
      Files.deleteIfExists(stateFile);
      System.out.println(json(Map.of("stopped", false)));
      return;
    }
    ProcessHandle process = ProcessHandle.of(state.pid).orElseThrow();
    signalProcessGroup(state.pid, "TERM");
    for (int attempt = 0; attempt < 50 && process.isAlive(); attempt++) {
      try { Thread.sleep(100); }
      catch (InterruptedException error) { Thread.currentThread().interrupt(); break; }
    }
    if (process.isAlive()) signalProcessGroup(state.pid, "KILL");
    Files.deleteIfExists(stateFile);
    System.out.println(json(Map.of("stopped", true, "pid", state.pid)));
  }

  /** 向由 setsid 创建的整个进程组发送终止信号。 */
  private static void signalProcessGroup(long pid, String signal) throws IOException {
    try {
      new ProcessBuilder("/bin/kill", "-" + signal, "--", "-" + pid).start().waitFor();
    }
    catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IOException("终止进程组时被中断", error);
    }
  }

  /** 判断命令行是否请求帮助信息。 */
  private static boolean isHelpRequest(String[] arguments) {
    return arguments.length == 0 || "help".equals(arguments[0]) || "--help".equals(arguments[0]) || "-h".equals(arguments[0]) || (arguments.length > 1 && ("--help".equals(arguments[1]) || "-h".equals(arguments[1])));
  }

  /** 返回顶层或指定子命令的可读帮助文本。 */
  static String helpText(String command) {
    if (command == null || "help".equals(command) || "--help".equals(command) || "-h".equals(command)) return """
      用法: simple-ide <command> [options]

      命令:
        build   使用开源 JPS 编译项目模块
        run     启动 IDEA Application 配置
        test    使用 JUnit Platform 执行测试类或方法
        status  查询应用 PID、端口和服务状态
        logs    查询应用日志尾部
        stop    停止应用进程

      使用 `simple-ide <command> --help` 查看子命令参数。
      """;
    return switch (command) {
      case "build" -> """
        用法: simple-ide build --project <path> [--module <name>] [--tests] [--rebuild] [--auto-rebuild]
               [--external-config <external_build_system>] [--config-dir <JetBrains config>]

        --rebuild 全量清理并重建；省略 --module 时编译全部模块。
        --auto-rebuild 仅在识别到 JPS 历史 class 缓存异常时自动全量重试一次；存在运行中的 Application 时不执行清理。
        Maven/Gradle 项目需传入已持久化的外置 JPS 模型目录。
        """;
      case "run" -> """
        用法: simple-ide run --project <path> --configuration <name>
               [--random-port | --port <number>] [--external-config <external_build_system>] [--java <path>]

        --random-port 自动选择端口并传入 --server.port；启动状态保存到项目 .simple-ide/runs/。
        """;
      case "test" -> """
        用法: simple-ide test --project <path> --module <name> --class <FQN>
               [--method <name>] [--external-config <external_build_system>]

        同时支持 JUnit 4 和 JUnit 5，返回 total/passed/failed/skipped 统计。
        """;
      case "status" -> """
        用法: simple-ide status --project <path> --configuration <name>

        返回 projectPath、pid、port 和 STARTING/RUNNING/STOPPED 状态。
        """;
      case "logs" -> """
        用法: simple-ide logs --project <path> --configuration <name> [--tail <lines>]

        默认返回最近 200 行日志，JSON 字段 lines 可直接供 AI 分析。
        """;
      case "stop" -> """
        用法: simple-ide stop --project <path> --configuration <name>

        先优雅停止，超时后强制结束，并删除运行状态。
        """;
      case "npm" -> """
        用法: simple-ide npm <run|status|logs|stop> --project <path> --script <name> [options]

        使用 `simple-ide npm run --help` 查看启动参数。
        """;
      case "npm run" -> """
        用法: simple-ide npm run --project <path> --script <name> [--random-port | --port <number>]
               [--port-argument <flag>] [--npm <path>] [--env KEY=VALUE]

        默认通过 `npm run <script> -- --port <port>` 传递端口。不同脚本可用 --port-argument -p 覆盖；--env 可重复传入。
        """;
      case "npm status" -> "用法: simple-ide npm status --project <path> --script <name>\n";
      case "npm logs" -> "用法: simple-ide npm logs --project <path> --script <name> [--tail <lines>]\n";
      case "npm stop" -> "用法: simple-ide npm stop --project <path> --script <name>\n";
      default -> "未知命令: " + command + "\n使用 `simple-ide --help` 查看可用命令。\n";
    };
  }

  /** 使用 JUnit Platform 执行指定测试类或方法，并输出统计 JSON。 */
  private static void test(Command command) throws Exception {
    Path project = command.project();
    String module = command.required("module");
    String externalConfig = command.option("external-config", null);
    Path testOutput = moduleOutput(project, module, externalConfig, true);
    Path productionOutput = moduleOutput(project, module, externalConfig, false);
    String classpath = testOutput + File.pathSeparator + runtimeClasspath(project, module, externalConfig, productionOutput);
    URL[] urls = Arrays.stream(classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))).map(entry -> {
      try { return Path.of(entry).toUri().toURL(); }
      catch (Exception error) { throw new IllegalArgumentException(error); }
    }).toArray(URL[]::new);
    ClassLoader previous = Thread.currentThread().getContextClassLoader();
    try (URLClassLoader loader = new URLClassLoader(urls, previous)) {
      Thread.currentThread().setContextClassLoader(loader);
      String testClass = command.required("class");
      String method = command.option("method", null);
      var selector = method == null ? DiscoverySelectors.selectClass(testClass) : DiscoverySelectors.selectMethod(testClass, method);
      var request = LauncherDiscoveryRequestBuilder.request().selectors(selector).build();
      SummaryGeneratingListener listener = new SummaryGeneratingListener();
      Launcher launcher = LauncherFactory.create();
      launcher.registerTestExecutionListeners(listener);
      launcher.execute(request);
      var summary = listener.getSummary();
      boolean success = summary.getTotalFailureCount() == 0;
      System.out.println(json(Map.of("success", success, "total", summary.getTestsFoundCount(), "passed", summary.getTestsSucceededCount(), "failed", summary.getTestsFailedCount(), "skipped", summary.getTestsSkippedCount())));
      if (!success) System.exit(1);
    }
    finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  /** 通过官方 JPS API 直接执行增量编译。 */
  private static void build(Command command) throws Exception {
    Path project = command.project();
    List<String> modules = command.values("module");
    Path cache = Path.of(System.getProperty("user.home"), ".cache", "simple-ide", cacheKey(project), "system");
    Files.createDirectories(cache);
    BuildAttempt initial = runBuild(project, cache, modules, command.flag("tests"), command.flag("rebuild"), command.values("file"), command.option("external-config", null), command.option("config-dir", null));
    if (!command.flag("auto-rebuild") || command.flag("rebuild") || initial.success || !isRecoverableIncrementalBuildFailure(initial.messages)) {
      printBuildResult(initial);
      return;
    }
    List<String> running = runningConfigurations(project);
    if (!running.isEmpty()) {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("success", false);
      result.put("problems", initial.problems());
      result.put("rebuildRequired", true);
      result.put("rebuildReason", "INCREMENTAL_CACHE_CORRUPTION");
      result.put("runningConfigurations", running);
      System.out.println(json(result));
      System.exit(1);
      return;
    }
    BuildAttempt rebuilt = runBuild(project, cache, modules, command.flag("tests"), true, command.values("file"), command.option("external-config", null), command.option("config-dir", null));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("success", rebuilt.success);
    result.put("problems", rebuilt.problems());
    result.put("rebuildTriggered", true);
    result.put("rebuildReason", "INCREMENTAL_CACHE_CORRUPTION");
    result.put("attempts", List.of(initial.problems(), rebuilt.problems()));
    System.out.println(json(result));
    if (!rebuilt.success) System.exit(1);
  }

  /** 执行一次 JPS 编译，并保留诊断供调用方决定是否恢复。 */
  private static BuildAttempt runBuild(Path project, Path cache, List<String> modules, boolean includeTests, boolean rebuild, List<String> files, String externalConfig, String configDirectory) throws Exception {
    List<BuildMessage> messages = new ArrayList<>();
    MessageHandler handler = messages::add;
    String previousExternalConfig = System.getProperty(GlobalOptions.EXTERNAL_PROJECT_CONFIG);
    if (externalConfig != null) System.setProperty(GlobalOptions.EXTERNAL_PROJECT_CONFIG, externalConfig);
    String globalOptions = configDirectory == null ? null : Path.of(configDirectory, "options").toString();
    BuildRunner runner = new BuildRunner(new JpsModelLoaderImpl(project.toString(), globalOptions, false, null));
    runner.setFilePaths(files.stream().map(path -> project.resolve(path).normalize().toString()).toList());
    ProjectDescriptor descriptor = runner.load(handler, cache, new BuildFSState(false));
    try {
      runner.runBuild(descriptor, CanceledStatus.NULL, handler, rebuild ? BuildType.PROJECT_REBUILD : BuildType.BUILD, buildScopes(modules, includeTests), true);
    }
    finally {
      descriptor.release();
      if (previousExternalConfig == null) System.clearProperty(GlobalOptions.EXTERNAL_PROJECT_CONFIG);
      else System.setProperty(GlobalOptions.EXTERNAL_PROJECT_CONFIG, previousExternalConfig);
    }
    List<Map<String, Object>> problems = messages.stream().map(SimpleIde::problem).toList();
    boolean success = messages.stream().noneMatch(message -> message.getKind() == BuildMessage.Kind.ERROR);
    return new BuildAttempt(success, messages.stream().map(BuildMessage::getMessageText).toList(), problems);
  }

  /** 输出普通构建结果，并以非零状态表示编译失败。 */
  private static void printBuildResult(BuildAttempt result) {
    System.out.println(json(Map.of("success", result.success, "problems", result.problems)));
    if (!result.success) System.exit(1);
  }

  /** 判断编译消息是否具有可由一次全量重建恢复的历史 class 缓存特征。 */
  static boolean isRecoverableIncrementalBuildFailure(List<String> messages) {
    String diagnostics = String.join("\n", messages).toLowerCase(Locale.ROOT);
    boolean staleAnonymousClass = diagnostics.matches("(?s).*\\$\\d+\\.class.*");
    boolean mapStructFailure = diagnostics.contains("mapstruct") || diagnostics.contains("mapping processor");
    boolean classReadFailure = diagnostics.contains("error reading") || diagnostics.contains("bad class file");
    return (staleAnonymousClass && mapStructFailure) || classReadFailure;
  }

  /** 返回项目中仍有存活进程的 Application 配置名称。 */
  private static List<String> runningConfigurations(Path project) throws IOException {
    Path directory = project.resolve(".simple-ide/runs");
    if (!Files.isDirectory(directory)) return List.of();
    List<String> running = new ArrayList<>();
    try (var files = Files.list(directory)) {
      for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".properties")).toList()) {
        try {
          RunState state = RunState.read(file);
          if (state != null && state.alive()) running.add(file.getFileName().toString().replaceFirst("\\.properties$", ""));
        }
        catch (RuntimeException ignored) {
          // 损坏的运行状态文件不应阻止编译；其 PID 无法被可信地识别。
        }
      }
    }
    return running;
  }

  /** 根据 IDEA Application 配置和 JPS 编译输出启动 Java 进程。 */
  private static void run(Command command) throws Exception {
    Path project = command.project();
    ApplicationConfiguration configuration = ApplicationConfiguration.load(project, command.required("configuration"));
    String externalConfig = command.option("external-config", null);
    Path output = moduleOutput(project, configuration.module, externalConfig, false);
    if (!Files.isDirectory(output)) {
      throw new IllegalStateException("未找到 JPS 编译输出：" + output + "；请先执行 build");
    }
    Path stateFile = runState(project, configuration.name);
    RunState current = RunState.read(stateFile);
    if (current != null && ProcessHandle.of(current.pid).map(ProcessHandle::isAlive).orElse(false)) {
      throw new IllegalStateException("Application 已在运行，PID=" + current.pid);
    }
    List<String> processCommand = new ArrayList<>();
    processCommand.add("setsid");
    processCommand.add(command.option("java", "java"));
    processCommand.addAll(split(configuration.vmParameters));
    Path runDirectory = project.resolve(".simple-ide/runs");
    Files.createDirectories(runDirectory);
    Path classpathJar = runDirectory.resolve(configuration.name + "-classpath.jar");
    writeClasspathJar(classpathJar, runtimeClasspath(project, configuration.module, externalConfig, output));
    processCommand.add("-cp");
    processCommand.add(output + File.pathSeparator + classpathJar);
    processCommand.add(configuration.mainClass);
    int port = configuredPort(command);
    if (port > 0) processCommand.add("--server.port=" + port);
    processCommand.addAll(split(configuration.programParameters));
    Path log = runDirectory.resolve(configuration.name + ".log");
    Process process = new ProcessBuilder(processCommand)
      .directory(configuration.workingDirectory(project).toFile())
      .redirectErrorStream(true)
      .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
      .start();
    RunState state = new RunState(process.pid(), log, port, project.toString(), processCommand);
    state.write(stateFile);
    System.out.println(json(state.toMap(port > 0 ? "STARTING" : "RUNNING")));
  }

  /** 查询 Application 进程状态。 */
  private static void status(Command command) throws IOException {
    Path project = command.project();
    Path stateFile = runState(project, command.required("configuration"));
    RunState state = RunState.read(stateFile);
    if (state == null || !state.alive()) {
      Files.deleteIfExists(stateFile);
      System.out.println(json(Map.of("projectPath", project.toString(), "status", "STOPPED", "running", false)));
      return;
    }
    String status = state.port > 0 && isPortOpen(state.port) ? "RUNNING" : "STARTING";
    System.out.println(json(state.toMap(status)));
  }

  /** 优雅停止 Application 进程。 */
  private static void stop(Command command) throws IOException {
    RunState state = RunState.read(runState(command.project(), command.required("configuration")));
    if (state == null || !state.alive()) {
      Files.deleteIfExists(runState(command.project(), command.required("configuration")));
      System.out.println(json(Map.of("stopped", false)));
      return;
    }
    ProcessHandle process = ProcessHandle.of(state.pid).orElseThrow();
    signalProcessGroup(state.pid, "TERM");
    for (int attempt = 0; attempt < 50 && process.isAlive(); attempt++) {
      try {
        Thread.sleep(100);
      }
      catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    boolean forced = false;
    if (process.isAlive()) {
      signalProcessGroup(state.pid, "KILL");
      forced = true;
    }
    Files.deleteIfExists(runState(command.project(), command.required("configuration")));
    System.out.println(json(Map.of("stopped", true, "pid", state.pid, "forced", forced)));
  }

  /** 返回指定 Application 日志的最近若干行，供 AI 快速诊断启动状态。 */
  private static void logs(Command command) throws IOException {
    Path project = command.project();
    String configuration = command.required("configuration");
    int lines = Integer.parseInt(command.option("tail", "200"));
    if (lines < 1) throw new IllegalArgumentException("--tail 必须大于 0");
    Path log = project.resolve(".simple-ide/runs/" + configuration + ".log");
    System.out.println(json(Map.of("projectPath", project.toString(), "configuration", configuration, "logPath", log.toString(), "lines", tail(log, lines))));
  }

  /** 读取文件末尾指定数量的文本行。 */
  static List<String> tail(Path file, int limit) throws IOException {
    if (!Files.isRegularFile(file)) return List.of();
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    return lines.subList(Math.max(0, lines.size() - limit), lines.size());
  }

  /** 解析固定端口或分配随机可用端口。 */
  private static int configuredPort(Command command) throws IOException {
    if (command.flag("random-port")) return findAvailablePort();
    String value = command.option("port", null);
    return value == null ? -1 : Integer.parseInt(value);
  }

  /** 向操作系统申请一个临时空闲 TCP 端口。 */
  static int findAvailablePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  /** 判断回环地址上的 TCP 端口是否已处于监听状态。 */
  static boolean isPortOpen(int port) {
    if (port <= 0) return false;
    try (Socket socket = new Socket("127.0.0.1", port)) {
      return true;
    }
    catch (IOException ignored) {
      return false;
    }
  }

  /** 将 JPS 编译诊断转换为稳定的命令行 JSON 结构。 */
  private static Map<String, Object> problem(BuildMessage problem) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("kind", problem.getKind().name());
    value.put("message", problem.getMessageText());
    return value;
  }

  /** 构造 Java production/test 目标的 JPS 编译范围。 */
  private static List<TargetTypeBuildScope> buildScopes(List<String> modules, boolean includeTests) {
    List<TargetTypeBuildScope> scopes = new ArrayList<>();
    for (JavaModuleBuildTargetType type : JavaModuleBuildTargetType.ALL_TYPES) {
      if (!includeTests && type.isTests()) continue;
      TargetTypeBuildScope.Builder scope = TargetTypeBuildScope.newBuilder().setTypeId(type.getTypeId()).setForceBuild(false);
      if (modules.isEmpty()) scope.setAllTargets(true);
      else scope.addAllTargetId(modules);
      scopes.add(scope.build());
    }
    return scopes;
  }

  /** 为项目生成稳定的增量缓存目录键。 */
  private static String cacheKey(Path project) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(project.toRealPath().toString().getBytes(StandardCharsets.UTF_8));
    StringBuilder key = new StringBuilder();
    for (byte value : digest) key.append(String.format("%02x", value));
    return key.toString();
  }

  /** 读取 IDEA 的项目输出目录。 */
  private static Path outputRoot(Path project) throws Exception {
    Path misc = project.resolve(".idea/misc.xml");
    if (!Files.isRegularFile(misc)) return project.resolve("out");
    NodeList outputs = document(misc).getElementsByTagName("output");
    if (outputs.getLength() == 0) return project.resolve("out");
    String url = ((Element)outputs.item(0)).getAttribute("url");
    return Path.of(expand(url.replaceFirst("^file://", ""), project));
  }

  /** 从外置 JPS 模块模型或标准输出目录确定模块输出路径。 */
  private static Path moduleOutput(Path project, String module, String externalConfig, boolean tests) throws Exception {
    if (externalConfig != null) {
      Path moduleFile = Path.of(externalConfig, "modules", module + ".xml");
      if (Files.isRegularFile(moduleFile)) {
        String tag = tests ? "output-test" : "output";
        NodeList outputs = document(moduleFile).getElementsByTagName(tag);
        if (outputs.getLength() > 0) {
          String url = ((Element)outputs.item(0)).getAttribute("url").replaceFirst("^file://", "").replace("$MODULE_DIR$", project.toAbsolutePath().toString());
          return Path.of(expand(url, project)).toAbsolutePath();
        }
      }
    }
    return outputRoot(project).resolve(tests ? "test" : "production").resolve(module).toAbsolutePath();
  }

  /** 组装模块输出、资源和 Maven 库的运行 classpath。 */
  private static String runtimeClasspath(Path project, String module, String externalConfig, Path output) throws Exception {
    List<String> entries = new ArrayList<>(List.of(output.toString(), project.resolve("src/main/resources").toString()));
    if (externalConfig != null) {
      Path libraries = Path.of(externalConfig, "project", "libraries.xml");
      if (Files.isRegularFile(libraries)) {
        NodeList roots = document(libraries).getElementsByTagName("root");
        for (int index = 0; index < roots.getLength(); index++) {
          String url = ((Element)roots.item(index)).getAttribute("url");
          if (url.startsWith("jar://") && url.endsWith("!/")) {
            Path jar = Path.of(url.substring(6, url.length() - 2).replace("$MAVEN_REPOSITORY$", Path.of(System.getProperty("user.home"), ".m2", "repository").toString()));
            if (Files.isRegularFile(jar) && !jar.getFileName().toString().endsWith("-sources.jar")) entries.add(jar.toString());
          }
        }
      }
    }
    entries.sort(Comparator.comparingInt(entry -> isProjectJsonLibrary(entry) ? 0 : 1));
    return String.join(File.pathSeparator, entries);
  }

  /** 判断当前 JAR 是否为项目声明的 org.json 实现，应优先于旧 Android JSON 兼容包加载。 */
  private static boolean isProjectJsonLibrary(String entry) {
    return entry.contains(File.separator + "org" + File.separator + "json" + File.separator + "json" + File.separator);
  }

  /** 创建含完整依赖 URI 的 MANIFEST JAR，以避免操作系统命令行长度限制。 */
  private static void writeClasspathJar(Path file, String classpath) throws IOException {
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    String entries = Arrays.stream(classpath.split(java.util.regex.Pattern.quote(File.pathSeparator)))
      .map(entry -> Path.of(entry).toUri().toString())
      .collect(java.util.stream.Collectors.joining(" "));
    manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, entries);
    try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(file), manifest)) {
      // 清单是唯一需要的 JAR 条目。
    }
  }

  /** 返回 Application 状态文件路径。 */
  private static Path runState(Path project, String configuration) {
    return project.resolve(".simple-ide/runs/" + configuration + ".properties");
  }

  /** 解析 XML 文件。 */
  private static org.w3c.dom.Document document(Path file) throws Exception {
    return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile());
  }

  /** 展开本实现支持的 IDEA 路径宏。 */
  private static String expand(String value, Path project) {
    return value.replace("$PROJECT_DIR$", project.toAbsolutePath().toString())
      .replace("$USER_HOME$", System.getProperty("user.home"));
  }

  /** 按空白分隔 JVM 或程序参数。 */
  private static List<String> split(String value) {
    return value.isBlank() ? List.of() : Arrays.asList(value.trim().split("\\s+"));
  }

  /** 将基本 Java 值序列化为一行 JSON。 */
  private static String json(Object value) {
    if (value == null) return "null";
    if (value instanceof String text) return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + '"';
    if (value instanceof Number || value instanceof Boolean) return value.toString();
    if (value instanceof Map<?, ?> map) return map.entrySet().stream().map(entry -> json(entry.getKey().toString()) + ":" + json(entry.getValue())).collect(java.util.stream.Collectors.joining(",", "{", "}"));
    if (value instanceof List<?> list) return list.stream().map(SimpleIde::json).collect(java.util.stream.Collectors.joining(",", "[", "]"));
    throw new IllegalArgumentException("不支持 JSON 值：" + value.getClass());
  }

  /** 表示解析后的 IDEA Application 配置。 */
  private record ApplicationConfiguration(String name, String mainClass, String module, String vmParameters, String programParameters, String workingDirectory) {
    /** 加载 Application 类型的 IDEA 运行配置。 */
    static ApplicationConfiguration load(Path project, String name) throws Exception {
      Path file = project.resolve(".idea/runConfigurations/" + name + ".xml");
      org.w3c.dom.Document source = Files.isRegularFile(file) ? document(file) : document(project.resolve(".idea/workspace.xml"));
      NodeList configurations = source.getElementsByTagName("configuration");
      Element configuration = null;
      for (int index = 0; index < configurations.getLength(); index++) {
        Element candidate = (Element)configurations.item(index);
        if (name.equals(candidate.getAttribute("name"))) { configuration = candidate; break; }
      }
      if (configuration == null || !"Application".equals(configuration.getAttribute("type"))) throw new IllegalArgumentException("配置不是 Application 类型：" + name);
      Map<String, String> options = new LinkedHashMap<>();
      NodeList optionNodes = configuration.getElementsByTagName("option");
      for (int index = 0; index < optionNodes.getLength(); index++) {
        Element option = (Element)optionNodes.item(index);
        options.put(option.getAttribute("name"), option.getAttribute("value"));
      }
      Element module = (Element)configuration.getElementsByTagName("module").item(0);
      String moduleName = options.getOrDefault("MODULE_NAME", module == null ? "" : module.getAttribute("name"));
      String mainClass = options.getOrDefault("MAIN_CLASS_NAME", "");
      if (moduleName.isBlank() || mainClass.isBlank()) throw new IllegalArgumentException("配置缺少模块或主类：" + name);
      return new ApplicationConfiguration(name, mainClass, moduleName, options.getOrDefault("VM_PARAMETERS", ""), options.getOrDefault("PROGRAM_PARAMETERS", ""), options.getOrDefault("WORKING_DIRECTORY", "$PROJECT_DIR$"));
    }

    /** 计算该配置的进程工作目录。 */
    Path workingDirectory(Path project) {
      return Path.of(expand(workingDirectory, project));
    }
  }

  /** 表示落盘保存的 Application 进程状态。 */
  private record RunState(long pid, Path log, int port, String projectPath, List<String> command) {
    /** 从状态文件加载运行记录。 */
    static RunState read(Path file) throws IOException {
      if (!Files.isRegularFile(file)) return null;
      List<String> values = Files.readAllLines(file, StandardCharsets.UTF_8);
      if (values.size() >= 4 && values.get(2).matches("-?\\d+")) {
        return new RunState(Long.parseLong(values.get(0)), Path.of(values.get(1)), Integer.parseInt(values.get(2)), values.get(3), values.subList(4, values.size()));
      }
      return new RunState(Long.parseLong(values.get(0)), Path.of(values.get(1)), -1, "", values.subList(2, values.size()));
    }

    /** 保存运行记录。 */
    void write(Path file) throws IOException {
      Files.createDirectories(file.getParent());
      List<String> values = new ArrayList<>(List.of(Long.toString(pid), log.toString(), Integer.toString(port), projectPath));
      values.addAll(command);
      Files.write(file, values, StandardCharsets.UTF_8);
    }

    /** 判断所记录的 Java 进程是否仍然存活。 */
    boolean alive() {
      return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /** 转换为命令行 JSON 输出。 */
    Map<String, Object> toMap(String status) {
      Map<String, Object> value = new LinkedHashMap<>();
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

  /** 表示一次 JPS 编译的成功状态、原始消息和 JSON 诊断。 */
  private record BuildAttempt(boolean success, List<String> messages, List<Map<String, Object>> problems) {
  }

  /** 表示已解析的命令名与参数。 */
  private record Command(String name, Map<String, List<String>> options) {
    /** 解析 --key value、--key=value 和布尔标记。 */
    static Command parse(String[] arguments) {
      if (arguments.length == 0) throw new IllegalArgumentException("用法：simple-ide <build|run|status|stop> --project <目录>");
      Map<String, List<String>> options = new LinkedHashMap<>();
      for (int index = 1; index < arguments.length; index++) {
        String argument = arguments[index];
        if (!argument.startsWith("--")) throw new IllegalArgumentException("无效参数：" + argument);
        String[] pair = argument.substring(2).split("=", 2);
        String key = pair[0];
        String value = pair.length == 2 ? pair[1] : index + 1 < arguments.length && !arguments[index + 1].startsWith("--") ? arguments[++index] : "true";
        options.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
      }
      return new Command(arguments[0], options);
    }

    /** 返回必填参数。 */
    String required(String name) {
      String value = option(name, null);
      if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少参数 --" + name);
      return value;
    }

    /** 返回可选参数或默认值。 */
    String option(String name, String fallback) {
      List<String> values = options.get(name);
      return values == null ? fallback : values.get(values.size() - 1);
    }

    /** 返回重复参数的全部值。 */
    List<String> values(String name) {
      return options.getOrDefault(name, List.of());
    }

    /** 判断布尔标记是否存在。 */
    boolean flag(String name) {
      return options.containsKey(name);
    }

    /** 返回规范化的项目根路径。 */
    Path project() {
      return Path.of(required("project")).toAbsolutePath().normalize();
    }
  }
}
