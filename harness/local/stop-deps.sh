#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
runtime_dir="$repo_root/.loop"
env_file="$runtime_dir/runtime.env"
[ -f "$env_file" ] || exit 0
# shellcheck disable=SC1090
source "$env_file"
stop_pid() { local pid="${1:-}"; [ -n "$pid" ] || return 0; kill "$pid" 2>/dev/null || true; for _ in 1 2 3 4 5; do kill -0 "$pid" 2>/dev/null || return 0; sleep 1; done; kill -9 "$pid" 2>/dev/null || true; }
safe_delete() {
  local dir="${1:-}"
  [ -n "$dir" ] || return 0
  [ -d "$dir" ] || return 0
  case "$dir" in
    "$runtime_dir"/redis.*|"$runtime_dir"/etcd.*) rm -rf "$dir" ;;
    *) echo "refusing unexpected dependency directory: $dir" >&2; return 1 ;;
  esac
}
stop_pid "${LOOP_REDIS_PID:-}"
stop_pid "${LOOP_ETCD_PID:-}"
safe_delete "${LOOP_REDIS_DIR:-}"
safe_delete "${LOOP_ETCD_DIR:-}"
rm -f "$env_file"
echo "local dependencies stopped"
