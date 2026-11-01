#!/bin/sh
# Usage: ./run.sh <vertx-version> <path/to/Issue.java> [args...]
# Resolves vertx-core <version> (and its Netty) with Maven and runs the single-file program.
# Netty leak detection is set to paranoid so buffer leaks are reported.
set -e
cd "$(dirname "$0")"
V="$1"; F="$2"; shift 2
CP_FILE="target/cp-$V.txt"
mkdir -p target
# Exit 2, not 1: the reproducers exit 1 when the bug reproduces
[ -s "$CP_FILE" ] || mvn -q -B dependency:build-classpath -Dvertx.version="$V" -Dmdep.outputFile="$CP_FILE" >&2 \
    || { rm -f "$CP_FILE"; echo "run.sh: could not resolve vertx-core $V" >&2; exit 2; }
exec java -Dio.netty.leakDetection.level=paranoid -cp "$(cat "$CP_FILE")" "$F" "$@"
