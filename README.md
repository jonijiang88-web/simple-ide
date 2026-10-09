# simple-ide

最小 Headless IDE 闭环。分发包内置 `lib/jps-cli.jar`，通过独立 JPS 进程编译，不依赖 IDEA 安装目录或 Community 构建产物。

## 闭环

```bash
./build.sh
./simple-ide build --project /path/to/project --module app
./simple-ide build --project /path/to/project --module app --auto-rebuild
./simple-ide run --project /path/to/project --configuration Application
./simple-ide debug --project /path/to/project --configuration Application
./simple-ide run --random-port --project /path/to/project --configuration Application
./simple-ide status --project /path/to/project --configuration Application
./simple-ide logs --project /path/to/project --configuration Application --tail 200
./simple-ide stop --project /path/to/project --configuration Application
./simple-ide test --project /path/to/project --module app --class com.example.AppTest
```

所有命令输出一行 JSON。JPS 增量缓存位于 项目 `.jps-cache/`；Application PID 与日志位于项目 `.simple-ide/` 目录。

`run` 默认采用纯运行模式（不挂 JDWP 调试代理），类加载与 JIT 深度优化全开，启动速度完全对齐 IDEA Run；若需进行断点单步调试，使用 `debug` 命令（或 `run --debug`）启动，自动开启 JDWP 远程调试（默认端口 5005，支持 `--debug-port <port>`）。

`build --auto-rebuild` 仅在诊断命中 MapStruct 读取历史匿名内部类产物、或 class 文件读取损坏等缓存特征时，自动执行一次全量重建。若项目存在运行中的 Application，命令不会清理输出目录，而是返回 `rebuildRequired`、`rebuildReason` 和 `runningConfigurations`；应先停止对应进程后重新执行构建。单独的 Lombok `builder()` 缺失不会触发重建，避免掩盖真实代码或配置问题。

`run --random-port` 会由 CLI 选择空闲端口并传入 `--server.port=<port>`。`status` 返回 `projectPath`、`pid`、`port`、`debugPort` 和 `status`：端口监听后为 `RUNNING`，初始化期间为 `STARTING`。进程退出后，CLI 自动删除陈旧状态文件，仅返回 `STOPPED`，不会返回过期 PID 或端口。

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

## 分发

Linux/macOS 运行 `./package.sh`，Windows PowerShell 运行 `.\package.ps1`；脚本强制重建并运行单元测试，生成同一个 `build/distributions/simple-ide.zip` 和 SHA-256 校验文件。

安装步骤、JDK/Maven 前置条件及平台能力边界见 [INSTALL.md](INSTALL.md)。Windows/macOS 的打包和安装入口已提供，本地服务启停仍需平台适配；不能将通用 ZIP 等同于全部功能已跨平台验证。
