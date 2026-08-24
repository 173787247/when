#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
env_file="$repo_root/.loop/runtime.env"
timeout=30
if [ "${1:-}" = "--timeout" ]; then timeout="${2:?timeout value required}"; shift 2; fi
requested=("$@")
if [ ${#requested[@]} -eq 0 ]; then requested=(redis etcd); fi
want() { local item; for item in "${requested[@]}"; do [ "$item" = "$1" ] && return 0; done; return 1; }
[ -f "$env_file" ] || { echo "runtime environment missing" >&2; exit 1; }
# shellcheck disable=SC1090
source "$env_file"
deadline=$((SECONDS + timeout))
while :; do
  ok=1
  if want redis && ! redis-cli -h 127.0.0.1 -p "$LOOP_REDIS_PORT" ping 2>/dev/null | grep -qx PONG; then ok=0; fi
  if want etcd && ! curl -fsS "http://127.0.0.1:$LOOP_ETCD_PORT/health" >/dev/null 2>&1; then ok=0; fi
  [ "$ok" = 1 ] && { echo "local dependencies ready"; exit 0; }
  [ "$SECONDS" -lt "$deadline" ] || { echo "dependencies did not become ready within ${timeout}s" >&2; exit 1; }
  sleep 1
done
