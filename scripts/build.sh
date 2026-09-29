#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[[ $# -le 1 ]] || { echo "Usage: $0 [--skip-tests]" >&2; exit 2; }
case "${1:-}" in
    "") skip_tests=false ;;
    --skip-tests) skip_tests=true ;;
    *) echo "Usage: $0 [--skip-tests]" >&2; exit 2 ;;
esac
case "$(uname -s)" in
    Darwin|Linux) ;;
    *) echo "error: only macOS and Linux builds are supported" >&2; exit 1 ;;
esac
for tool in java javac mvn native-image git cc; do
    command -v "$tool" >/dev/null || { echo "error: $tool is required" >&2; exit 1; }
done
if ! "$skip_tests"; then
    command -v python3 >/dev/null || { echo "error: Python 3 is required for e2e tests" >&2; exit 1; }
fi
java_version=$(java -version 2>&1)
[[ "$java_version" == *'version "25.'* ]] || { echo "error: Java 25 is required" >&2; exit 1; }
mvn -B -q clean package
if ! "$skip_tests"; then ./scripts/test-e2e.sh --jvm; fi
mkdir -p dist
native-image -jar target/gitlines.jar -o dist/gitlines
if ! "$skip_tests"; then ./scripts/test-e2e.sh --native; fi
printf '\nExecutable: %s/dist/gitlines\nSize: %s bytes\n' "$PWD" "$(wc -c < dist/gitlines | tr -d ' ')"
