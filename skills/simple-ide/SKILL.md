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

- `build` 默认 JPS；未显式指定引擎且 JPS 不可用时，可能回退到 IDEA MCP。显式 `--engine jps` 避免该回退；检查退出码和 JSON 的 `success`、`engine`、`problems`。
- JPS 生成 `target/classes`、`.jps-cache/` 和依赖 classpath 缓存；构建会同步项目资源。`--rebuild` / `--auto-rebuild` 请求清理重编；不要为普通增量构建默认启用。
- `test` 默认本地 JPS/JUnit，显式 `--engine jps` 可避免回退。`--skip-build` 跳过前置增量编译直接复用已有测试产物。测试失败或前置编译失败时 CLI 进程返回非零退出码（1）；JSON 与控制台输出包含所有失败项（`failures` 包含测试方法、异常类型、提示、代码定位与完整堆栈）及持久化报告路径（`reportFile`，固定最新报告为 `.simple-ide/reports/test-report.json`）。
- `--timeout` 单位为秒。编译或测试前遵循目标项目的授权与规则。编译过程实时向 stderr 输出进度日志，避免长时间无输出假死。

## Java 应用运行与调试

```bash
# 1. 纯运行（对齐 IDEA Run，极致性能，不挂 JDWP）
"$SIMPLE_IDE" run --project "$PROJECT_DIR" --configuration <name> --random-port [--skip-build]

# 2. 调试运行（对齐 IDEA Debug，自动开启 JDWP 远程调试）
"$SIMPLE_IDE" debug --project "$PROJECT_DIR" --configuration <name> --random-port [--debug-port <port>]
# 或
"$SIMPLE_IDE" run --project "$PROJECT_DIR" --configuration <name> --random-port --debug [--debug-port <port>]
```

- `run` 默认本地进程**纯运行模式**（不挂 JDWP 调试代理），类加载与 JIT C2 优化全开，获得对齐 IDEA Run 的极致启动速度；`--engine idea-mcp` 才请求 IDEA 托管。
- `debug` 命令（或 `run --debug`）对齐 IDEA Debug 模式，自动开启 JDWP 远程调试（默认端口 `5005`，可通过 `--debug-port <port>` 自定义）；返回 JSON 与 `status` 中暴露 `debugPort` 字段，方便 VS Code 等客户端随时轻量 Attach 调试。
- 本地运行读取 `.idea/runConfigurations/<name>.xml`，不存在时读取 `.idea/workspace.xml` 中同名 Application 配置。这里的 XML 是配置文件，不代表启动 IDEA。
- 配置提供主类、模块、VM 参数、程序参数和工作目录；classpath 来自 `.jps-cache/classpath-*.cache`。
- 默认先执行 JPS 增量编译。`--skip-build` 跳过增量编译直接复用已有 class 与 classpath 缓存，毫秒级快速拉起。
- `--port <n>` 固定端口；`--random-port` 选空闲端口并注入 `--server.port`；两者择一。
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
- 状态文件在 `.simple-ide/runs/`；Java 状态 `RUNNING` 仍需业务健康检查确认就绪。
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
