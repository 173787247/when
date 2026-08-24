#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
runtime_dir="$repo_root/.loop"
env_file="$runtime_dir/runtime.env"
mkdir -p "$runtime_dir"

requested=("$@")
if [ ${#requested[@]} -eq 0 ]; then requested=(redis etcd); fi
want() { local item; for item in "${requested[@]}"; do [ "$item" = "$1" ] && return 0; done; return 1; }
free_port() { python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()'; }
alive() { [ -n "${1:-}" ] && kill -0 "$1" 2>/dev/null; }

if [ -f "$env_file" ]; then
  # shellcheck disable=SC1090
  source "$env_file"
fi

if want redis && ! alive "${LOOP_REDIS_PID:-}"; then
  redis_port="$(free_port)"
  redis_dir="$(mktemp -d "$runtime_dir/redis.XXXXXX")"
  redis-server --bind 127.0.0.1 --port "$redis_port" --save '' --appendonly no --dir "$redis_dir" --daemonize yes --pidfile "$redis_dir/redis.pid" --logfile "$redis_dir/redis.log"
  for _ in 1 2 3 4 5 6 7 8 9 10; do [ -s "$redis_dir/redis.pid" ] && break; sleep 0.1; done
  [ -s "$redis_dir/redis.pid" ] || { cat "$redis_dir/redis.log" >&2; exit 1; }
  redis_pid="$(tr -d '[:space:]' < "$redis_dir/redis.pid")"
  LOOP_REDIS_PID="$redis_pid"; LOOP_REDIS_PORT="$redis_port"; LOOP_REDIS_DIR="$redis_dir"
fi

if want etcd && ! alive "${LOOP_ETCD_PID:-}"; then
  etcd_client_port="$(free_port)"
  etcd_peer_port="$(free_port)"
  etcd_dir="$(mktemp -d "$runtime_dir/etcd.XXXXXX")"
  etcd --name loop-etcd --data-dir "$etcd_dir" --listen-client-urls "http://127.0.0.1:$etcd_client_port" --advertise-client-urls "http://127.0.0.1:$etcd_client_port" --listen-peer-urls "http://127.0.0.1:$etcd_peer_port" --initial-advertise-peer-urls "http://127.0.0.1:$etcd_peer_port" --initial-cluster "loop-etcd=http://127.0.0.1:$etcd_peer_port" --initial-cluster-state new >"$etcd_dir/etcd.log" 2>&1 &
  LOOP_ETCD_PID=$!; LOOP_ETCD_PORT="$etcd_client_port"; LOOP_ETCD_DIR="$etcd_dir"
fi

tmp_env="$env_file.tmp"
{
  printf 'LOOP_REDIS_PID=%q\n' "${LOOP_REDIS_PID:-}"
  printf 'LOOP_REDIS_PORT=%q\n' "${LOOP_REDIS_PORT:-}"
  printf 'LOOP_REDIS_DIR=%q\n' "${LOOP_REDIS_DIR:-}"
  printf 'LOOP_ETCD_PID=%q\n' "${LOOP_ETCD_PID:-}"
  printf 'LOOP_ETCD_PORT=%q\n' "${LOOP_ETCD_PORT:-}"
  printf 'LOOP_ETCD_DIR=%q\n' "${LOOP_ETCD_DIR:-}"
  printf 'WHEN_REDIS_HOST=127.0.0.1\nWHEN_REDIS_PORT=%q\n' "${LOOP_REDIS_PORT:-}"
  printf 'WHEN_ETCD_ENDPOINTS=http://127.0.0.1:%q\n' "${LOOP_ETCD_PORT:-}"
  printf 'REDIS_URL=redis://127.0.0.1:%q\n' "${LOOP_REDIS_PORT:-}"
  printf 'ETCD_ENDPOINTS=http://127.0.0.1:%q\n' "${LOOP_ETCD_PORT:-}"
} > "$tmp_env"
mv "$tmp_env" "$env_file"
printf 'local dependencies started: redis=%s etcd=%s\n' "${LOOP_REDIS_PORT:-not-requested}" "${LOOP_ETCD_PORT:-not-requested}"
