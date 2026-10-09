# simple-ide 安装与打包

同一个 `simple-ide.zip` 包含 Linux/macOS 启动脚本和 Windows `.bat` 启动脚本，不包含 JDK、Maven 或业务工程。

## 打包（维护者）

源码构建需要 JDK 17+，Gradle 的 Java 8 编译工具链也需可用。JPS 预构建依赖位于源码的 `lib/jps-cli.jar`。

Linux/macOS：

```bash
export JAVA_HOME=/你的/JDK17目录
./package.sh
```

Windows PowerShell：

```powershell
$env:JAVA_HOME = 'C:\你的\JDK17目录'
.\package.ps1
```

脚本强制重建并运行单元测试，失败即停止；成功生成 `build/distributions/simple-ide.zip` 及 `simple-ide.zip.sha256`。将 ZIP 发给使用者即可。

## 安装（使用者）

运行 CLI 需要 JDK 8+。Java 工程依赖解析可能调用 Maven，需准备 `mvn`、仓库权限和依赖。源码编译使用 JPS，无需打开 IDEA。

Linux/macOS：

```bash
unzip simple-ide.zip
cd simple-ide
./install.sh
export PATH="$HOME/.local/bin:$PATH"
simple-ide --help
```

Windows PowerShell：

```powershell
Expand-Archive .\simple-ide.zip -DestinationPath .
cd simple-ide
.\install.ps1
simple-ide.bat --help
```

Windows 默认安装到 `%LOCALAPPDATA%\simple-ide`，脚本只为当前终端添加 PATH；永久使用需将该目录的 `bin` 加入用户 PATH。若执行策略阻止脚本，可使用 `powershell -ExecutionPolicy Bypass -File .\install.ps1`。Linux/macOS 默认安装到 `~/.local/share/simple-ide`，入口为 `~/.local/bin/simple-ide`。技能默认安装到 `~/.agents/skills/simple-ide`；可用 `--no-skills`（Shell）或 `-NoSkills`（PowerShell）跳过。

多 JDK 环境：Linux/macOS 可通过 `SIMPLE_IDE_JAVA_HOME` 或 `JAVA_HOME` 选择 CLI JDK，Windows 使用 `JAVA_HOME`；将同一 JDK 的 `bin` 放在 PATH 首位供编译子进程使用。应用运行的 JDK 可显式传入 `--java`。

```bash
simple-ide build --engine jps --project /项目目录
simple-ide run --engine local --project /项目目录 --configuration Application --java /JDK目录/bin/java
```

## 平台支持范围

- Linux：已验证安装、JPS 构建及本地应用运行。
- macOS：启动器已移除 GNU `readlink -f` 依赖，提供安装/打包脚本；未在 macOS 实机验证。
- 进程级活跃探测与应用管控已跨平台适配 Linux（/proc 探测）、macOS（ps + lsof 探测）及 Windows（tasklist/taskkill），非 Linux 环境做全局异常安全拦截并平滑回退至持久化注册表，确保绝不报错。
- `run` 读取项目 `.idea/runConfigurations` 或 `.idea/workspace.xml` 的配置；这些文件无需启动 IDEA。无依赖工程若未生成 classpath 缓存，当前 `run` 可能找不到主类。
- IDEA 专用 `--engine idea-mcp` 另需 IDEA、对应插件与 MCP 配置。
