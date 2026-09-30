#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
JAVA_HOME="/usr/lib/jvm/java-1.17.0-openjdk-amd64" ./gradlew installDist distZip distTar
