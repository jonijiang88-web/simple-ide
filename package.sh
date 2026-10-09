#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./gradlew test distZip --rerun-tasks
cd build/distributions
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum simple-ide.zip > simple-ide.zip.sha256
else
  shasum -a 256 simple-ide.zip > simple-ide.zip.sha256
fi
printf 'Package: %s/simple-ide.zip\n' "$PWD"
