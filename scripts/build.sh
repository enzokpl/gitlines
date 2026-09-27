#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
case "${1:-}" in
    "") skip_tests=false ;;
    --skip-tests) skip_tests=true ;;
    *) echo "Usage: $0 [--skip-tests]" >&2; exit 2 ;;
esac
for tool in java javac mvn native-image git; do
    command -v "$tool" >/dev/null || { echo "error: $tool is required" >&2; exit 1; }
done
java_version=$(java -version 2>&1)
[[ "$java_version" == *'version "25.'* ]] || { echo "error: Java 25 is required" >&2; exit 1; }
mvn -B -q clean package
if ! "$skip_tests"; then ./scripts/test-e2e.sh --jvm; fi
mkdir -p dist
native-image -jar target/gitlines.jar -o dist/gitlines
if ! "$skip_tests"; then ./scripts/test-e2e.sh --native; fi
printf '\nExecutable: %s/dist/gitlines\nSize: %s bytes\n' "$PWD" "$(wc -c < dist/gitlines | tr -d ' ')"
