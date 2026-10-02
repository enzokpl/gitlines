#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
case "${1:---jvm}" in
    --jvm) cli=(java -jar "$PWD/target/gitlines.jar") ;;
    --native) cli=("$PWD/dist/gitlines") ;;
    *) echo "Usage: $0 [--jvm|--native] [workers]" >&2; exit 2 ;;
esac
if [[ $# -ge 2 ]]; then cli+=(--workers "$2"); fi
"${cli[@]}" --help >/dev/null
"${cli[@]}" --version | grep -q '^gitlines '
python3 scripts/e2e.py "${cli[@]}"
