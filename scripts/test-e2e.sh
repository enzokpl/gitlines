#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
case "${1:---jvm}" in
    --jvm) cli=(java -jar "$PWD/target/gitlines.jar") ;;
    --native) cli=("$PWD/dist/gitlines") ;;
    *) echo "Usage: $0 [--jvm|--native]" >&2; exit 2 ;;
esac
"${cli[@]}" --help >/dev/null
"${cli[@]}" --version | grep -q '^gitlines '
echo "CLI smoke tests passed."
