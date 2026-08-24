#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
node_env="$repo_root/.loop/single-node.env"
[ -f "$node_env" ] || exit 0
# shellcheck disable=SC1090
source "$node_env"

pid="${LOOP_SINGLE_NODE_PID:-}"
if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
  kill "$pid" 2>/dev/null || true
  for _ in 1 2 3 4 5; do
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  kill -9 "$pid" 2>/dev/null || true
fi
rm -f "$node_env"
printf 'single-node server stopped: pid=%s\n' "${pid:-unknown}"
