# Simple IDE 安装与配置教程

`simple-ide` 是一个专为 AI 编程智能体（Agent）和开发者打造的轻量级 IDE 命令行工具。它通过 **IDEA MCP Server** 驱动本地 IntelliJ IDEA 进行项目打开、编译、运行、测试与日志追踪。

---

## 1. 前置依赖要求

在安装与使用 `simple-ide` 前，请确保环境满足以下要求：

### 1.1 Java 运行时
- **运行 CLI：JDK 8+**（支持 8 / 11 / 17 / 21 等任意版本；产物以 Java 8 编译兼容，无需专门安装高版本 JDK）
- **源码二次构建（可选）：JDK 17+**（仅当从源码执行 `./gradlew` 构建时，Gradle 9 自身才需要 JDK 17 宿主环境）
- 若系统存在多个 Java 版本，可通过环境变量 `SIMPLE_IDE_JAVA_HOME` 或 `JAVA_HOME` 指定。

### 1.2 IntelliJ IDEA 与 MCP 插件
- **IntelliJ IDEA 2024.2+**（Ultimate 或 Community）
- 在 IDEA 中安装并启用 **`idea-mcp-ext`** 插件（或配置 MCP Server 扩展）
- **MCP SSE 默认端口**：`64342`（如修改，可通过环境变量 `export IJ_MCP_SERVER_PORT=<port>` 同步）

---

## 2. 分发包内容结构

解压后的分发包（`simple-ide.zip` 或 `simple-ide.tar`）包含以下结构：

```text
simple-ide/
├── bin/                          # CLI 可执行文件
│   ├── simple-ide-launcher       # 自动探测 JDK 版本的启动器
│   ├── simple-ide                # Linux/macOS 脚本
│   └── simple-ide.bat            # Windows 脚本
├── lib/                          # 依赖 JAR 包集合
├── skills/                       # AI Agent 技能配置
│   └── simple-ide/
│       ├── SKILL.md              # 智能体指令与操作指南
│       └── agents/openai.yaml    # 技能元数据声明
├── install.sh                    # 一键安装脚本
├── INSTALL.md                    # 本安装教程
└── README.md                     # 项目介绍与命令文档
```

---

## 3. 快速安装（推荐）

解压分发包后，直接执行根目录下的安装脚本：

```bash
# 默认安装 CLI 到 ~/.local/bin，安装 Skills 到 ~/.agents/skills/simple-ide
./install.sh
```

### 自定义安装选项：

```bash
# 指定 CLI 安装前缀（例如安装到 /usr/local/bin，需要 sudo）
sudo ./install.sh --prefix /usr/local

# 指定 Skills 目录
./install.sh --skill-dir ~/.my-agents/skills/simple-ide

# 仅安装 CLI，不安装 Skills
./install.sh --no-skills

# 开发模式：使用软链接代替文件拷贝
./install.sh --link
```

---

## 4. 手动安装步骤

如果不使用安装脚本，也可以按照以下步骤手动配置：

### 步骤 1：安装 CLI
将解压后的 `simple-ide` 文件夹放到目标路径（如 `~/.local/share/simple-ide`），并将 `bin/simple-ide-launcher` 软链到用户的 PATH 目录下：

```bash
mkdir -p ~/.local/bin ~/.local/share
cp -r . ~/.local/share/simple-ide
ln -sf ~/.local/share/simple-ide/bin/simple-ide-launcher ~/.local/bin/simple-ide
chmod +x ~/.local/bin/simple-ide
```

确保 `~/.local/bin` 已加入用户的 `PATH` 环境变量（通常在 `~/.bashrc` 或 `~/.zshrc` 中）：
```bash
export PATH="$HOME/.local/bin:$PATH"
```

### 步骤 2：安装 Agent Skills
将 `skills/simple-ide` 目录复制到当前智能体框架的 Skills 目录：

```bash
# 标准推荐目录（~/.agents/skills/）
mkdir -p ~/.agents/skills
cp -r skills/simple-ide ~/.agents/skills/

# 若使用 Antigravity/Gemini CLI（可选）：
mkdir -p ~/.gemini/config/skills
cp -r skills/simple-ide ~/.gemini/config/skills/
```

---

## 5. 验证安装

运行以下命令，验证 CLI 是否安装成功：

```bash
# 查看帮助信息
simple-ide --help

# 验证打开工程（若 IDEA 未启动会自动启动并等待 MCP 端口）
simple-ide open --project /path/to/your/project

# 验证通过 IDEA MCP 编译模块
simple-ide build --project /path/to/your/project --module <module-name>
```

---

## 6. 在 AI 编程智能体中使用

在具备 Agent Skills 支持的 AI 编程助手（如 Antigravity、OpenCode、Codex 等）中：
- 智能体读取 `~/.agents/skills/simple-ide/SKILL.md` 后，会自动学会通过 `simple-ide` 执行项目的打开、编译与测试。
- 在提示词中可直接指示：“使用 simple-ide 编译并运行单测”。

---

## 7. 常见问题排查（FAQ）

1. **`simple-ide requires JDK 8+. Set SIMPLE_IDE_JAVA_HOME...`**
   - 解决方案：系统缺少兼容的 JDK，或请显式设置 `export SIMPLE_IDE_JAVA_HOME=/path/to/jdk`（支持 JDK 8 及以上）。
2. **`Failed to connect to IDEA MCP Server at http://127.0.0.1:64342/`**
   - 解决方案：确保 IDEA 处于运行状态，并确认已在 IDEA 插件中启用了 MCP Server；若监听非 64342 端口，设置 `export IJ_MCP_SERVER_PORT=<实际端口>`。
3. **编译报 `Lock held by another process`**
   - 解决方案：同一项目存在并发编译任务，`simple-ide` 内置编译排队锁，等待前序任务完成或排查残留进程。
