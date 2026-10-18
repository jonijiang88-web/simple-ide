# simple-ide

最小 Headless IDE 闭环。分发包内置 `lib/jps-cli.jar`，通过独立 JPS 进程编译，不依赖 IDEA 安装目录或 Community 构建产物。

## 闭环

```bash
./build.sh
./simple-ide build --project /path/to/project --module app
./simple-ide build --project /path/to/project --module app --auto-rebuild
./simple-ide build-status --project /path/to/project --tail 20
./simple-ide version
./simple-ide run --project /path/to/project --configuration Application
./simple-ide debug --project /path/to/project --configuration Application
./simple-ide run --random-port --project /path/to/project --configuration Application
./simple-ide run --random-port --project /path/to/project --configuration Application --hot-reload
./simple-ide reload --project /path/to/project --configuration Application
./simple-ide status --project /path/to/project --configuration Application
./simple-ide logs --project /path/to/project --configuration Application --tail 200
./simple-ide stop --project /path/to/project --configuration Application
./simple-ide test --project /path/to/project --module app --class com.example.AppTest
```

所有命令输出一行 JSON。JPS 增量缓存位于 项目 `.jps-cache/`；依赖 Classpath 缓存位于项目 `.jps-deps/`（独立于 `.jps-cache`，避免被 JPS 存储损坏时清理）；构建状态与日志位于 `.simple-ide/builds/`；Application PID 与日志位于项目 `.simple-ide/` 目录。

`run` 默认采用纯运行模式（不挂 JDWP 调试代理），类加载与 JIT 深度优化全开，启动速度完全对齐 IDEA Run；若需进行断点单步调试，使用 `debug` 命令（或 `run --debug`）启动，自动开启 JDWP 远程调试（默认端口 5005，支持 `--debug-port <port>`）。

`run --hot-reload` 仅支持 Spring Boot + `--engine local`：把内置 `lib/spring-boot-devtools-2.7.18-simpleide.jar` 注入应用 classpath，并设置 `spring.devtools.restart.enabled=true` 与 trigger file。该 jar 基于上游 2.7.18，补丁为 `RestartClassLoader` 增加 parent-first 精确类列表（默认覆盖 Artemis 中 3 个纯 Spring 覆盖类），用于兼容应用输出目录覆盖依赖类的场景；若覆盖类自身引用应用代码（如 `DefaultWebResponseExceptionTranslator`），用重复的 `--hot-reload-include <url-regex>` 把对应依赖 jar 加入 restart classpath。随后 `simple-ide reload` 用 JPS 增量编译到共享 `target/classes`，再更新 `.simple-ide/devtools/trigger` 触发 DevTools 重启。该模式是开发期显式取舍：直接写运行中应用的 `target/classes`，且每次 reload 会重建整个 Spring 上下文（所有 Bean 重建、内存状态丢失），不是 JRebel 式按类热重载。若 classpath 存在 JRebel/HotSwapAgent，DevTools 会自动禁用重启，CLI 会直接拒绝。

设置环境变量 `SIMPLE_IDE_HOTSWAP=1` 可启用 simple-ide 内置的方法体热替换 agent（与 `--hot-reload` 互斥）：

```bash
SIMPLE_IDE_HOTSWAP=1 ./simple-ide run --project /path/to/project --configuration Application
SIMPLE_IDE_HOTSWAP=1 ./simple-ide reload --project /path/to/project --configuration Application
```

该模式通过 `-javaagent:lib/simple-ide-hotswap-agent.jar` 注入，`reload` 把 JPS 编译出的 class 交给 agent 执行 `redefineClasses`，**不重启 Spring context**，通常 1 秒级生效。只支持方法体变化；加字段/方法/改签名会返回 `SCHEMA_CHANGE_REQUIRES_RESTART`，需要重启或后续版本化能力。`build` 在启用该环境变量时会创建 `.simple-ide/hotswap/` 工作目录并校验 agent jar。

`build --auto-rebuild` 先执行增量编译，仅在诊断命中 MapStruct 读取历史匿名内部类产物、或 class 文件读取损坏等缓存特征时，自动执行一次全量重建。`build --rebuild` 则是直接清理重建，二者语义不同：显式 `--rebuild` 不会额外叠加自动重建，运行中的应用会阻止清理。若项目存在运行中的 Application，命令不会清理输出目录，而是返回 `rebuildRequired`、`rebuildReason` 和 `runningConfigurations`；应先停止对应进程后重新执行构建。单独的 Lombok `builder()` 缺失或真实源码错误不会触发重建，避免掩盖真实代码或配置问题。

构建超时从子进程启动时刻开始计时（不再等到输出读完才计时），超时/取消会先记录后代进程身份（PID + 启动时间令牌）、再优雅终止、最后对忽略 TERM 的存活后代强制终止并独立确认；根进程退出不作为整棵树已退出的依据。若无法枚举进程表或仍无法确认退出，会返回 `PROCESS_TREE_NOT_TERMINATED` 并列出存活 PID，绝不谎报完整回收（Java 8 下使用 `/proc`，Java 9+ 使用 `ProcessHandle`，POSIX 回退 `ps`；Windows 上若无法确认则同样返回未确认）。"未完整构建"标记在**真正启动前**写入 `.simple-ide/builds/incomplete.json`，只有构建成功才清除；下一次构建据此先自动执行一次全量重建。等待本地构建锁支持 `--lock-timeout <sec>` 超时。

`build --module <artifactId>` 按模块限定构建范围（自动包含依赖闭包）；JPS 不支持按文件粒度作用域，传入 `--file` 会显式拒绝（`UNSUPPORTED_FILE_SCOPE`），不会静默忽略。多模块项目的 `run`/`test` 依据 RunConfiguration 或测试源码所在模块选择精确输出目录与逐模块依赖缓存；`run` 在启动前会先检查同配置实例是否已在运行，避免隐式前置构建在旧实例存活时覆盖共享输出。

依赖解析不再允许「失败后继续用残缺依赖」：缓存必须在指纹与「全部 Jar 存在」校验下有效，否则先调用 Maven 解析。**静态解析只包含直接 Jar，无法构造传递依赖闭包（依赖自身 POM/profile/BOM/scope/optional/exclusion/冲突调解都不在覆盖范围内），因此不再被当作「证明完整」**：Maven 不可用或失败时，只有两种可确证例外才继续——① 模块声明的依赖为空且父 POM 无继承依赖；② 全部依赖都由 reactor 模块图提供（且无 `dependencyManagement`/`profiles`/父级 `dependencies` 等可能引入隐形依赖的语义）。其余情况一律以 `DEPENDENCY_RESOLUTION_FAILED`（退出码 4）硬失败并保留 `.jps-deps/maven-resolve-*.log` 证据，不写入有效缓存。Maven 可执行文件可用 `-Dsimple.ide.maven.binary` 覆盖（受限环境与确定性测试）。

JPS 的增量存储在打开失败时会**先删除缓存目录（`.jps-cache`）再全量重建**，该行为在 JPS 内部且没有开关可关闭。项目采取三层措施：

1. **删除探针（不依赖 mtime/条目数）**：构建前在实际使用的存储目录内放置哨兵文件，构建后检查其是否消失——只有整根目录被删除才会消失。因此全新的首次构建、普通增量编译（新增条目、更新 mtime）不会再被误判为内部重建。
2. **识别性预检**：可判定的损坏（枚举器文件截断/不可读/头部不一致、零字节存储文件、`version.dat` 异常）在未授权时直接拒绝启动并返回 `JPS_CACHE_CORRUPTED_REBUILD_REQUIRED`；无法确证健康的布局（未知枚举器版本、无枚举器但有其它存储内容）标记为「不确定」。
3. **受保护隔离构建（缓存 + 编译输出一起隔离）**：只要**存在运行中应用且未授权清理**，构建默认改用
   `.simple-ide/isolated-build/cache`（隔离增量缓存）与 `.simple-ide/isolated-build/out/<module>/{classes,test-classes}`
   （隔离编译输出，经 jps-cli `--output-root` 重定向）。JPS 内置的「删除存储根目录再全量重建」既不会删除共享 `.jps-cache`，
   也**不会删除/改写运行中应用实际使用的 `target/classes`、`target/test-classes` 与资源**（实测：共享 class 字节哈希、
   资源与哨兵文件在隔离构建的失败/成功/超时三种情况下全部保持不变）。
   - **不发布（延后发布语义）**：隔离构建成功也**不会**把产物发布到共享 `target`，结果 JSON 显式返回
     `isolatedBuild=true`、`isolatedOutputs`（模块→隔离 classes/testClasses）、`outputsPublished=false`、`targetOutputsStale=true`。
     要更新共享输出，请先 `simple-ide stop` 停止应用，再执行普通 `build`（或 `--rebuild`）。
   - **隔离缓存从空状态开始，禁止复制共享缓存**：JPS 的增量存储记录了「上一次构建的产物路径」，若把共享存储副本当隔离存储，
     JPS 在重建时会按记录删除**共享 target** 下的产物，反而破坏运行中应用所用的 class。
   - **`run` 在本项目已有应用运行时直接拒绝**（`RUN_BLOCKED_BY_RUNNING_APP`），避免用「未发布」的隔离结果当作 `target` 已更新；
     **`test` 会自动使用隔离输出**（结果 JSON 带 `isolatedBuild=true`），不会跑在陈旧 class 上。
   - `--isolate-cache` 可在无运行应用时也强制隔离；`--no-isolate-cache` 关闭该保护（回到「仅隔离缓存 + 事后上报」的旧行为）。

运行中的应用仍会阻止一切清理入口（显式 `--rebuild`、`--auto-rebuild` 重建、未完整标记升级重建），统一返回 `rebuildRequired`/`rebuildReason`/`runningConfigurations`，且不会清除未完整标记。父命令生命周期方面：`build` 期间父进程收到 `SIGTERM` 会通过关闭钩子取消并回收 JPS 子进程树、把标记更新为 `cli_terminated`；父进程被 `SIGKILL` 时无法执行任何清理（操作系统限制），此时 JPS 子进程会继续持有项目构建锁 `.simple-ide/jps-build.lock`，下一次构建检测到存活的 JPS 进程会以 `ORPHAN_JPS_BUILD_DETECTED` 拒绝启动，避免并发写同一缓存；需先结束残留进程（不自动杀业务/用户进程）。

构建失败统一返回 `errorCode`、`phase` 与结构化 `diagnostics`（含 `severity`/`file`/`line`/`column`/`message`）；退出码语义：JPS 编译失败=1，存储损坏需重建=3，依赖解析失败=4，构建锁被占用=5。`stdout` 只输出稳定单行 JSON（测试命令同样只输出一行测试结果 JSON），编译进度、心跳与详细日志走 `stderr` 与构建日志文件。

`simple-ide build-status [--build <buildId|latest>] [--tail <lines>]` 返回真实阶段（`QUEUED`/`DEPENDENCY_RESOLVE`/`COMPILE_MAIN`/`COMPILE_TEST`/`REBUILD`/`FINISHED`）、`pid`、`jpsPid`（进程创建后立即登记，运行期间即可查到存活构建进程）、`startedAt`、`durationMs`、`logFile`、`exitCode`、`errorCode` 与 `state`；不提供百分比或 ETA。`simple-ide version` 打印工具版本、缓存格式版本与内置 `jps-cli.jar` 的路径/大小/SHA-256，用于确认分发构建与安装版本一致。

依赖 Classpath 缓存写入会附带 `.meta` 指纹元数据（模块与整条父 POM 链内容、Maven settings 摘要、JDK 与工具版本、缓存格式版本），父 POM 或模块 POM 变更会使缓存失效；缓存条目（含旧格式缓存）中只要有一个 Jar 缺失就不会被采纳。缓存与构建状态均以「临时文件 + 原子替换」写入，settings 只取摘要，不会记录或暴露任何凭据。

`run --random-port` 会由 CLI 选择空闲端口并传入 `--server.port=<port>`。`status` 返回 `projectPath`、`pid`、`port`、`debugPort` 和 `status`：端口监听后为 `RUNNING`，初始化期间为 `STARTING`。进程退出后，CLI 自动删除陈旧状态文件，仅返回 `STOPPED`，不会返回过期 PID 或端口。

未指定 `--port/--random-port` 时无需手工补端口：`status`/`list`/`npm status` 会从进程监听套接字自动探测主端口（过滤 DevTools LiveReload `35729` 与 JDWP 调试端口；多候选时优先采信启动日志里框架自报的端口，否则取最小端口），探测结果只用于输出与状态判定，不回写状态文件。

前端 npm 服务使用相同的进程管理逻辑：

```bash
./simple-ide npm run --project /path/to/frontend --script dev --random-port
./simple-ide npm status --project /path/to/frontend --script dev
./simple-ide npm logs --project /path/to/frontend --script dev --tail 200
./simple-ide npm stop --project /path/to/frontend --script dev
```

可通过重复的 `--env KEY=VALUE` 传入前端环境变量。汇联易重构项目支持 `--env BACKEND_URL=http://127.0.0.1:<backend-port>`，将 `/api`、`/config`、`/invoice` 等业务代理转发到本地 Artemis。

`npm run` 启动前会校验 `package.json` 声明的直接依赖是否已安装在 `node_modules`，缺失时快速失败并提示执行 `npm install`，避免依赖不同步时静默起一个编译报错的开发服务；如需跳过校验可加 `--skip-deps-check`。

`npm status` 不再只看端口监听：它结合端口与最近一次编译结果判定，编译报错时返回 `FAILED` 并附 `compileStatus`（`failed`/`ok`/`unknown`）与 `compileEvidence`（失败明细行），编译成功为 `RUNNING`，端口未监听为 `STARTING`，进程退出为 `STOPPED`。

已内置 `jps-cli` 增量构建与测试引擎：自动解析 Maven `pom.xml` 依赖与模块拓扑，优先通过 JPS 执行秒级增量构建（零改动 ~700ms，单文件改动 ~1.5s），并结合内置 JUnit 平台执行本地测试，无需打开 IDEA 界面或运行 MCP 插件。

本地测试使用随工具打包的 JUnit Platform、Jupiter（含参数化测试）及 Vintage。测试子进程会替换项目中这些组件的同名旧版 Jar，避免新版 launcher 与旧版 commons/engine 混用；项目输出、业务依赖和第三方测试扩展仍保留。该替换只作用于测试进程，不修改项目依赖配置或应用运行类路径。

## 分发

Linux/macOS 运行 `./package.sh`，Windows PowerShell 运行 `.\package.ps1`；脚本强制重建并运行单元测试，生成同一个 `build/distributions/simple-ide.zip` 和 SHA-256 校验文件。

安装步骤、JDK/Maven 前置条件及平台能力边界见 [INSTALL.md](INSTALL.md)。Windows/macOS 的打包和安装入口已提供，本地服务启停仍需平台适配；不能将通用 ZIP 等同于全部功能已跨平台验证。
