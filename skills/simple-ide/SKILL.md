---
name: simple-ide
description: "使用 simple-ide 启动、编译、测试、检查和停止本地 Java 后端（通过 IDEA MCP）及前端服务。支持 IDEA MCP 编译测试与本地集成联调。"
---

# Simple IDE (Local Runtime & Build CLI)

## 可覆盖路径（先解析再执行）

本 skill 涉及核心 CLI 与项目目录，**必须在动手前解析为具体路径**，后续所有命令一律引用这些变量、不得随意写死绝对路径。

- `SIMPLE_IDE`：simple-ide 可执行文件路径。
- `BACKEND_DIR`：后端工程根目录（含 `pom.xml` 或 `build.gradle`）。
- `FRONTEND_DIR`：前端工程根目录（含 `package.json`）。

解析优先级（从高到低）：

1. 用户在当前对话里明确给出的路径，以用户指定为准；
2. 任务上下文里已建立的工作空间（如需求交付分支目录）；
3. 环境变量 `SIMPLE_IDE_BIN` / `ARTEMIS_BACKEND_DIR` / `ARTEMIS_FRONTEND_DIR`；
4. 系统 PATH 中的 `simple-ide`（可通过 `which simple-ide` 获取）；
5. 默认回退路径（如 `~/.local/bin/simple-ide` 或项目本地可执行文件）。

解析完后，在执行前明确最终取值。

## 编译引擎

simple-ide 底层通过 **IDEA MCP Server** 调用 `build_module_with_dependencies` 编译，不再使用 JPS 或 Maven。

前置条件：
- IDEA 2024.2+ 已安装 **idea-mcp-ext** 插件
- IDEA 已打开目标项目（如未打开，用 `simple-ide open` 自动打开）
- IDEA MCP SSE 端口可达（默认 `64342`，可通过 `IJ_MCP_SERVER_PORT` 环境变量覆盖）

编译命令不再需要 `--external-config` 和 `--config-dir` 参数。

## CLI 命令一览

```bash
# 1. 打开项目（IDEA 未运行时自动启动）
simple-ide open   --project <path>

# 2. IDEA MCP 编译项目模块（默认超时 1800s）
simple-ide build  --project <path> [--module <name>] [--timeout <sec>]

# 3. IDEA MCP 运行单元测试（JUnit 4/5）
simple-ide test   --project <path> --class <FQN> [--method <name>]

# 4. 启动 Application 配置
simple-ide run    --project <path> --configuration <name> [--random-port | --port <n>]

# 5. 查询应用运行状态
simple-ide status --project <path> --configuration <name>

# 6. 查看应用日志输出
simple-ide logs   --project <path> --configuration <name> [--tail <n>]

# 7. 停止应用进程
simple-ide stop   --project <path> --configuration <name>

# 8. 前端 npm 服务管理
simple-ide npm <run|status|logs|stop> --project <path> --script <name> [--random-port | --port <n>] [--env K=V]
```

## 规范与安全原则

- 启动任一服务前先用 `status` 确认状态。除非用户明确要求重启或有必要配置变更，否则避免重复重启运行中的服务。
- 严禁泄露敏感信息：将 OAuth 请求体、Basic 授权值、Access Token、Refresh Token 及 API 敏感响应体视为保密内容，不得打印或记录。
- 诊断日志优先使用 `logs --tail 80` 查看最新增量，避免大文件读取刷屏。

## 常见流程示例

### 1. 后端编译与启动

```bash
# 确保 IDEA 打开项目并连接 MCP
simple-ide open --project "$BACKEND_DIR"

# 触发依赖编译
simple-ide build --project "$BACKEND_DIR" --module app --timeout 1800

# 启动并在后台托管
simple-ide run --project "$BACKEND_DIR" --configuration Application --random-port
simple-ide status --project "$BACKEND_DIR" --configuration Application
```

### 2. 前端服务启动与代理注入

```bash
simple-ide npm run --random-port --project "$FRONTEND_DIR" \
  --script dev \
  --env BACKEND_URL=http://127.0.0.1:<backend-port>

simple-ide npm status --project "$FRONTEND_DIR" --script dev
```

### 3. 单测执行

```bash
simple-ide test --project "$BACKEND_DIR" --class com.example.service.UserServiceTest --method testGetUserById
```
