#!/usr/bin/env bash
set -euo pipefail
[[ $# -eq 1 ]] || { echo "Usage: $0 /path/to/repository" >&2; exit 2; }
project_root=$(cd "$(dirname "$0")/.." && pwd)
executable="$project_root/dist/gitlines"
[[ -x "$executable" ]] || { echo "error: run ./scripts/build.sh first" >&2; exit 1; }
printf 'Executable: %s\nSize: %s bytes\n' "$executable" "$(wc -c < "$executable" | tr -d ' ')"
printf 'Three total application runs (including Git). Peak RSS is OS-reported, not combined process memory.\n'
for iteration in 1 2 3; do
    printf '\nRun %s\n' "$iteration"
    if [[ ! -x /usr/bin/time ]]; then
        time "$executable" "$1" >/dev/null
        continue
    fi
    case "$(uname -s)" in
        Darwin) /usr/bin/time -l "$executable" "$1" >/dev/null ;;
        Linux) /usr/bin/time -v "$executable" "$1" >/dev/null ;;
        *) time "$executable" "$1" >/dev/null ;;
    esac
done
