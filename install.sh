#!/usr/bin/env bash
# simple-ide CLI 及 Skills 一键安装脚本
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 默认参数
PREFIX="${PREFIX:-$HOME/.local}"
INSTALL_SKILLS=true
SKILLS_TARGET_DIR="${SKILLS_TARGET_DIR:-$HOME/.agents/skills/simple-ide}"
LINK_MODE=false

usage() {
  cat <<EOF
simple-ide 安装脚本

用法:
  ./install.sh [选项]

选项:
  --prefix <dir>       CLI 安装目标前缀 (默认: \$HOME/.local，二进制将放置在 <dir>/bin)
  --skill-dir <dir>    Skills 安装目标目录 (默认: \$HOME/.agents/skills/simple-ide)
  --no-skills          不安装 Agent Skills
  --link               使用符号链接代替拷贝 (适合开发阶段)
  -h, --help           显示此帮助信息
EOF
  exit 0
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --prefix)
      PREFIX="$2"
      shift 2
      ;;
    --skill-dir)
      SKILLS_TARGET_DIR="$2"
      shift 2
      ;;
    --no-skills)
      INSTALL_SKILLS=false
      shift
      ;;
    --link)
      LINK_MODE=true
      shift
      ;;
    -h|--help)
      usage
      ;;
    *)
      echo "未知参数: $1" >&2
      usage
      ;;
  esac
done

echo "========================================"
echo "      Simple IDE 安装程序"
echo "========================================"

# 1. 寻找分发产物或构建产物
SOURCE_DIR=""
if [[ -d "$SCRIPT_DIR/bin" && -d "$SCRIPT_DIR/lib" ]]; then
  # 当前目录本身就是解压后的分发包
  SOURCE_DIR="$SCRIPT_DIR"
elif [[ -d "$SCRIPT_DIR/build/install/simple-ide/bin" && -d "$SCRIPT_DIR/build/install/simple-ide/lib" ]]; then
  # 当前目录是源码工程根目录
  SOURCE_DIR="$SCRIPT_DIR/build/install/simple-ide"
elif [[ -f "$SCRIPT_DIR/gradlew" ]]; then
  echo "检测到工程尚未构建，正在构建 installDist..."
  "$SCRIPT_DIR/build.sh"
  SOURCE_DIR="$SCRIPT_DIR/build/install/simple-ide"
else
  echo "错误: 未找到 simple-ide 的 bin 和 lib 产物目录！" >&2
  exit 1
fi

# 2. 安装 CLI
TARGET_BIN_DIR="$PREFIX/bin"
TARGET_APP_DIR="$PREFIX/share/simple-ide"
mkdir -p "$TARGET_BIN_DIR"

echo "-> 正在安装 CLI 到 $PREFIX ..."
if [[ "$LINK_MODE" == "true" ]]; then
  echo "  使用软链接模式..."
  ln -sf "$SOURCE_DIR/bin/simple-ide-launcher" "$TARGET_BIN_DIR/simple-ide"
else
  rm -rf "$TARGET_APP_DIR"
  mkdir -p "$TARGET_APP_DIR"
  cp -r "$SOURCE_DIR/bin" "$TARGET_APP_DIR/"
  cp -r "$SOURCE_DIR/lib" "$TARGET_APP_DIR/"
  ln -sf "$TARGET_APP_DIR/bin/simple-ide-launcher" "$TARGET_BIN_DIR/simple-ide"
fi
chmod +x "$TARGET_BIN_DIR/simple-ide"
echo "  CLI 安装完成: $TARGET_BIN_DIR/simple-ide"

# 3. 安装 Skills
if [[ "$INSTALL_SKILLS" == "true" ]]; then
  SKILLS_SRC=""
  if [[ -d "$SCRIPT_DIR/skills/simple-ide" ]]; then
    SKILLS_SRC="$SCRIPT_DIR/skills/simple-ide"
  elif [[ -d "$SOURCE_DIR/skills/simple-ide" ]]; then
    SKILLS_SRC="$SOURCE_DIR/skills/simple-ide"
  fi

  if [[ -n "$SKILLS_SRC" ]]; then
    echo "-> 正在安装 Agent Skills..."
    
    # 目标1：~/.agents/skills/simple-ide
    mkdir -p "$(dirname "$SKILLS_TARGET_DIR")"
    rm -rf "$SKILLS_TARGET_DIR"
    cp -r "$SKILLS_SRC" "$SKILLS_TARGET_DIR"
    echo "  已安装到: $SKILLS_TARGET_DIR"

    # 目标2：若存在 ~/.gemini/config/skills 则同步更新
    GEMINI_SKILLS="$HOME/.gemini/config/skills/simple-ide"
    if [[ -d "$HOME/.gemini/config/skills" ]]; then
      mkdir -p "$(dirname "$GEMINI_SKILLS")"
      rm -rf "$GEMINI_SKILLS"
      cp -r "$SKILLS_SRC" "$GEMINI_SKILLS"
      echo "  已同步到: $GEMINI_SKILLS"
    fi
  else
    echo "  提示: 未找到 skills 目录，跳过 Skills 安装。"
  fi
fi

# 4. PATH 检查
echo ""
echo "========================================"
echo "安装完成！"
echo "========================================"

if [[ ":$PATH:" != *":$TARGET_BIN_DIR:"* ]]; then
  echo "注意: $TARGET_BIN_DIR 不在当前系统的 PATH 环境变量中。"
  echo "请将以下内容添加到 ~/.bashrc 或 ~/.zshrc:"
  echo "  export PATH=\"$TARGET_BIN_DIR:\$PATH\""
  echo ""
fi

echo "验证命令:"
echo "  $TARGET_BIN_DIR/simple-ide --help"
