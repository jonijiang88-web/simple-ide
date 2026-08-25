#!/usr/bin/env bash
set -euo pipefail

JAVA_HOME="/usr/lib/jvm/java-1.17.0-openjdk-amd64" ./gradlew build installDist
