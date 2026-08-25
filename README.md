# simple-ide

最小 Headless IDE 闭环。Gradle 从 JetBrains 官方 Maven 仓库下载开源 JPS 依赖，CLI 直接调用 `BuildRunner`，不依赖 IDEA 安装目录或 Community 构建产物。

## 闭环

```bash
./build.sh
./simple-ide build --project /path/to/project --module app
./simple-ide build --project /path/to/project --module app --auto-rebuild
./simple-ide run --project /path/to/project --configuration Application
./simple-ide run --random-port --project /path/to/project --configuration Application
./simple-ide status --project /path/to/project --configuration Application
./simple-ide logs --project /path/to/project --configuration Application --tail 200
./simple-ide stop --project /path/to/project --configuration Application
./simple-ide test --project /path/to/project --module app --class com.example.AppTest
```

所有命令输出一行 JSON。JPS 增量缓存位于 `~/.cache/simple-ide/`；Application PID 与日志位于项目 `.simple-ide/` 目录。

`build --auto-rebuild` 仅在诊断命中 MapStruct 读取历史匿名内部类产物、或 class 文件读取损坏等缓存特征时，自动执行一次全量重建。若项目存在运行中的 Application，命令不会清理输出目录，而是返回 `rebuildRequired`、`rebuildReason` 和 `runningConfigurations`；应先停止对应进程后重新执行构建。单独的 Lombok `builder()` 缺失不会触发重建，避免掩盖真实代码或配置问题。

`run --random-port` 会由 CLI 选择空闲端口并传入 `--server.port=<port>`。`status` 返回 `projectPath`、`pid`、`port` 和 `status`：端口监听后为 `RUNNING`，初始化期间为 `STARTING`。进程退出后，CLI 自动删除陈旧状态文件，仅返回 `STOPPED`，不会返回过期 PID 或端口。

前端 npm 服务使用相同的进程管理逻辑：

```bash
./simple-ide npm run --project /path/to/frontend --script dev --random-port
./simple-ide npm status --project /path/to/frontend --script dev
./simple-ide npm logs --project /path/to/frontend --script dev --tail 200
./simple-ide npm stop --project /path/to/frontend --script dev
```

可通过重复的 `--env KEY=VALUE` 传入前端环境变量。汇联易重构项目支持 `--env BACKEND_URL=http://127.0.0.1:<backend-port>`，将 `/api`、`/config`、`/invoice` 等业务代理转发到本地 Artemis。

Maven/Gradle 项目需先拥有外置 JPS 模型。可使用 `--external-config <external_build_system>` 与 `--config-dir <JetBrains config>` 传入已持久化模型和 SDK 表；当前 CLI 不负责导入 Maven/Gradle 模型。

## 分发

运行 `./gradlew test distZip` 后分发 `build/distributions/simple-ide.zip`。解压后使用 `bin/simple-ide-launcher`；它会选择 JDK 17+，也可通过 `SIMPLE_IDE_JAVA_HOME` 明确指定。
# simple-ide
