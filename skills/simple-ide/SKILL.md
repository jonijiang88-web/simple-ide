---
name: simple-ide
description: 使用 simple-ide 一站式 CLI 编译、运行、测试及集中管控本地进程和 npm 脚本。用于工具命令、参数、引擎和状态排查；业务工程的启动配置与前后端对接由项目技能维护。
---

# Simple IDE CLI

## 工具定位与版本

优先使用用户指定的可执行文件，其次 `SIMPLE_IDE_BIN`、PATH 中的 `simple-ide`；分发版默认安装为 `~/.local/bin/simple-ide`（Linux/macOS）或 `%LOCALAPPDATA%\simple-ide\bin\simple-ide.bat`（Windows）。示例中的 `SIMPLE_IDE`、`PROJECT_DIR` 先解析成具体路径。

查看对应子命令帮助，不因全局帮助仍描述 IDEA 就判断所有命令依赖 IDEA。帮助未列出的参数以当前安装版本的实现或实际返回为准，不凭旧技能猜测。

```bash
"$SIMPLE_IDE" --help
"$SIMPLE_IDE" run --help
"$SIMPLE_IDE" status --help
"$SIMPLE_IDE" npm run --help
```

本技能只维护工具契约，不指定业务仓库、Spring profile、代理路由、服务启动顺序或 E2E 配置。

## 编译与测试

```bash
"$SIMPLE_IDE" build --engine jps --project "$PROJECT_DIR" --timeout 600
"$SIMPLE_IDE" test --engine jps --project "$PROJECT_DIR" --class <FQN> [--method <name>]
```

- `build` 默认 JPS；未显式指定引擎且 JPS 不可用时，可能回退到 IDEA MCP。显式 `--engine jps` 避免该回退；检查退出码和 JSON 的 `success`、`engine`、`errorCode`、`phase`、`diagnostics`、`buildId`、`logFile`。
- 失败诊断是结构化的：`diagnostics[]` 每项含 `severity`/`file`/`line`/`column`/`message`（旧字段 `problems` 保持兼容）；`stdout` 只输出稳定单行 JSON，编译进度与心跳走 `stderr`。
- JPS 生成 `target/classes`、`.jps-cache/`（JPS 增量编译数据）与 `.jps-deps/classpath-<module>.cache`（依赖 Classpath 缓存，独立目录，避免被 JPS 在增量存储损坏时整体清理 `.jps-cache` 而误删）；构建会同步项目资源。
- `--rebuild` 直接清理重建；`--auto-rebuild` 先增量，仅在命中缓存/历史 class 损坏特征时自动重建一次（真实源码错误与 Lombok `builder()` 缺失不触发）。不要为普通增量构建默认启用。存在运行中的 Application 时清理会被拒绝，返回 `rebuildRequired`/`rebuildReason`/`runningConfigurations`。
- 超时从子进程启动时刻计时；超时/取消会先记录后代进程身份（PID+启动时间）再优雅终止，继而对忽略 TERM 的存活后代强杀并独立确认，根进程退出不算整棵树已退出。无法确认时返回 `PROCESS_TREE_NOT_TERMINATED` 并列出存活 PID（Java 8 用 `/proc`，Java 9+ 用 `ProcessHandle`，POSIX 回退 `ps`；无法枚举即视为未确认）。`--lock-timeout <sec>` 控制等锁超时。
- 未完整构建标记在**启动前**写入、仅成功后清除；下一次构建据此先全量重建一次。
- JPS 内部会在存储损坏时先删 `.jps-cache` 再重建且无开关可关。三层措施：①**删除探针**（存储目录内哨兵，消失才说明整根被删）避免正常首次/增量构建被误判；②可识别损坏在未授权时直接拒绝启动（`JPS_CACHE_CORRUPTED_REBUILD_REQUIRED`）；③**受保护隔离构建**——存在运行中应用且未授权清理时默认同时隔离「增量缓存 + 编译输出」（`.simple-ide/isolated-build/{cache,out/<module>/{classes,test-classes}}`），JPS 的删除重建既不碰共享 `.jps-cache` 也不碰运行中应用使用的 `target/*`（实测失败/成功/超时下共享 class 哈希与资源不变）。隔离结果**不发布**：JSON 返回 `isolatedBuild=true`/`isolatedOutputs`/`outputsPublished=false`/`targetOutputsStale=true`，要更新共享输出需先 `stop` 再普通 `build`；`run` 在项目已有应用运行时直接拒绝（`RUN_BLOCKED_BY_RUNNING_APP`），`test` 自动改用隔离输出。隔离缓存必须从空状态开始（复制共享存储会让 JPS 按记录删共享产物）。`--isolate-cache` 强制、`--no-isolate-cache` 关闭。共享路径上确认被删则返回 `JPS_INTERNAL_REBUILD_NOT_AUTHORIZED`。
- 依赖解析失败不再继续用残缺依赖：缓存需通过指纹与「Jar 齐全」校验；**静态解析只有直接 Jar、无法证明传递闭包**，Maven 不可用时仅两种例外可继续：①零依赖且父 POM 无继承依赖；②全部依赖由 reactor 模块图提供（无 `dependencyManagement`/`profiles`/父级 `dependencies`）。其余一律 `DEPENDENCY_RESOLUTION_FAILED`（退出码 4）且不写有效缓存。
- 父进程 `SIGTERM` 会经关闭钩子回收 JPS 子进程树并保留标记；父进程 `SIGKILL` 无法清理（平台限制），JPS 子进程持有 `.simple-ide/jps-build.lock`，下一次构建以 `ORPHAN_JPS_BUILD_DETECTED` 拒绝并发写缓存，需先结束残留进程。
- `--module <artifactId>` 限定构建范围（含依赖闭包）；JPS 不支持 `--file` 粒度，传入会显式拒绝（`UNSUPPORTED_FILE_SCOPE`）。退出码：编译失败=1、缓存损坏需重建=3、依赖解析失败=4、构建锁被占用=5。
- `test` 默认本地 JPS/JUnit，显式 `--engine jps` 可避免回退。`--skip-build` 跳过前置增量编译直接复用已有测试产物。测试失败、容器初始化失败（`CONTAINER_FAILED`）、未发现测试（`NO_TESTS_FOUND`）或前置编译失败时 CLI 进程返回非零退出码（1）；`success=false` 只由失败/未执行导致，合理跳过（`skipped`）不算失败。JSON 含 `failed`/`skipped`/`aborted`/`executed`/`notExecuted`/`containersFailed`、全部失败项（含容器级 `container:true`）与报告路径 `reportFile`（最新报告为 `.simple-ide/reports/test-report.json`）。
- 多模块项目的 `run`/`test` 按 RunConfiguration 或测试源码所在模块选择输出目录与逐模块依赖缓存；无法唯一确定时显式报错，不静默取第一个缓存。
- `--timeout` 单位为秒。编译或测试前遵循目标项目的授权与规则。编译过程实时向 stderr 输出进度与心跳，避免长时间无输出假死。
- 查询构建状态与版本：`build-status [--build <id|latest>] [--tail <lines>]` 返回真实阶段/`pid`/`jpsPid`（运行期间即可查到存活构建进程）/`startedAt`/`durationMs`/`logFile`/`exitCode`/`errorCode`/`state`（不提供百分比或 ETA）；`version` 打印工具版本、缓存格式版本与 `jps-cli.jar` 的 SHA-256。

## Java 应用运行与调试

```bash
# 1. 纯运行（对齐 IDEA Run，极致性能，不挂 JDWP）
"$SIMPLE_IDE" run --project "$PROJECT_DIR" --configuration <name> --random-port [--skip-build]

# 2. 调试运行（对齐 IDEA Debug，自动开启 JDWP 远程调试）
"$SIMPLE_IDE" debug --project "$PROJECT_DIR" --configuration <name> --random-port [--debug-port <port>]
# 或
"$SIMPLE_IDE" run --project "$PROJECT_DIR" --configuration <name> --random-port --debug [--debug-port <port>]

# 3. Spring Boot 热更新（开发期，全量 context 重启）
"$SIMPLE_IDE" run --project "$PROJECT_DIR" --configuration <name> --random-port --hot-reload
"$SIMPLE_IDE" reload --project "$PROJECT_DIR" --configuration <name>
```

- `run --hot-reload` 仅支持 Spring Boot + `--engine local`：注入内置 `lib/spring-boot-devtools-2.7.18.jar`，设置 DevTools trigger-file；`reload` 会 JPS 增量编译到共享 `target/classes` 并更新 `.simple-ide/devtools/trigger`，由 DevTools 重启整个 Spring 上下文。会重建所有 Bean、丢失内存状态，不是 JRebel 式按类热重载；classpath 存在 JRebel/HotSwapAgent 时 DevTools 会自动禁用重启，CLI 直接拒绝。

- `run` 默认本地进程**纯运行模式**（不挂 JDWP 调试代理），类加载与 JIT C2 优化全开，获得对齐 IDEA Run 的极致启动速度；`--engine idea-mcp` 才请求 IDEA 托管。
- `debug` 命令（或 `run --debug`）对齐 IDEA Debug 模式，自动开启 JDWP 远程调试（默认端口 `5005`，可通过 `--debug-port <port>` 自定义）；返回 JSON 与 `status` 中暴露 `debugPort` 字段，方便 VS Code 等客户端随时轻量 Attach 调试。
- 本地运行读取 `.idea/runConfigurations/<name>.xml`，不存在时读取 `.idea/workspace.xml` 中同名 Application 配置。这里的 XML 是配置文件，不代表启动 IDEA。
- 配置提供主类、模块、VM 参数、程序参数和工作目录；classpath 按运行配置的模块解析（`target/classes`/`target/test-classes` 与 `.jps-deps/classpath-<module>.cache`，回退兼容旧位置 `.jps-cache/classpath-*.cache`）；缓存列出的 Jar 缺失时会显式告警，不静默使用残缺缓存。
- 默认先执行 JPS 增量编译。`--skip-build` 跳过增量编译直接复用已有 class 与 classpath 缓存，毫秒级快速拉起。
- `--port <n>` 固定端口；`--random-port` 选空闲端口并注入 `--server.port`；两者择一。**都不指定时，端口由 `status`/`list`/`npm status` 从进程监听套接字自动探测**（过滤 DevTools LiveReload `35729` 与 JDWP 调试端口；多候选时优先采信启动日志里框架自报的端口，否则取最小端口），无需事先声明。
- 返回 `STARTING` / `running:true` 只代表进程已创建，不证明应用就绪。返回的 `pid`、`port`、`debugPort`、`log` 用于后续检查。

## 状态、集中管控与停止

```bash
"$SIMPLE_IDE" status --engine local --project "$PROJECT_DIR" --configuration <name>
"$SIMPLE_IDE" list [--project "$PROJECT_DIR"] [--running-only] [--clean] [--stop-all] [--table]
"$SIMPLE_IDE" logs --project "$PROJECT_DIR" --configuration <name> --tail 80
"$SIMPLE_IDE" stop --engine local --project "$PROJECT_DIR" --configuration <name>
"$SIMPLE_IDE" report [--project "$PROJECT_DIR"] [--no-open]
```

- `list` 集中管控所有由 simple-ide 启动的应用。不带 `--project` 时自动汇聚全局已知项目的所有服务，展示应用名、类型（java/npm）、PID、状态、端口、调试端口与日志；`--running-only` 仅看存活应用；`--clean` 清理失效状态记录；`--stop-all` 一键集中停止所有应用；`--table` 以整齐表格格式输出。
- 当前版本 `status/stop` 默认 `local`；进程不存在时返回停止状态并清理过期状态，不回退 MCP。显式 `--engine idea-mcp` 只连接已运行的 MCP，不可用则报错。
- `report` 生成并自动在浏览器中打开本地 HTML 运行仪表盘（`.simple-ide/reports/report.html`），展示关联应用（配置、PID、端口、调试端口、状态与日志）、最近测试执行结果与失败清单、以及命令历史记录。`--no-open` 仅生成文件而不主动打开浏览器。
- 状态文件在 `.simple-ide/runs/`；构建状态与日志在 `.simple-ide/builds/`；Java 状态 `RUNNING` 仍需业务健康检查确认就绪。状态里的端口优先取启动时的 `--port/--random-port`，未指定时自动探测进程监听端口（进程未监听则保持 `STARTING`，页面/接口是否就绪仍以业务健康检查为准）。
- 日志默认追加，避免把历史启动成功文案当成本轮结果；优先读取本轮尾部，不输出完整日志或完整 classpath 命令。

## npm 脚本管理

```bash
"$SIMPLE_IDE" npm run --project "$PROJECT_DIR" --script <name> \
  --random-port [--env KEY=VALUE]
"$SIMPLE_IDE" npm status --project "$PROJECT_DIR" --script <name>
"$SIMPLE_IDE" npm logs --project "$PROJECT_DIR" --script <name> --tail 80
"$SIMPLE_IDE" npm stop --project "$PROJECT_DIR" --script <name>
```

- 启动前校验 `package.json` 声明的依赖；`--skip-deps-check` 仅跳过校验，不会安装缺失模块。
- 端口作为 `--port` 参数传给 npm script，并设置 `PORT`；脚本使用其它参数时指定 `--port-argument <flag>`。
- `--env KEY=VALUE` 可重复传入，在子进程中注入环境变量。
- npm 状态结合 PID、端口和最近编译日志；退出进程清理过期状态。npm 命令不依赖 IDEA。

## IDEA 专用能力

`--engine idea-mcp` 请求已运行 MCP 的能力。仅在任务选择 IDEA 工作方式时使用，不作为本地 CLI 的前置步骤。

## 打包与平台边界

- 维护者：Linux/macOS `./package.sh`，Windows PowerShell `.\package.ps1`，输出 `build/distributions/simple-ide.zip` 和 SHA-256 校验文件；源码构建需要 JDK 17+ 和 Java 8 编译工具链。
- 安装：Linux/macOS `./install.sh`；Windows `.\install.ps1`。
- 进程级活跃探测与应用管控已跨平台适配 Linux（/proc 探测）、macOS（ps + lsof 探测）及 Windows（jps/tasklist/taskkill），非 Linux 环境做全局异常安全拦截并平滑回退至持久化注册表，确保绝不报错。
- 业务依赖解析可能调用 Maven；JPS 编译不等于无需安装 Maven。
