#!/usr/bin/env bash
# Docker-based local deps for WHEN E2E on Windows/WSL when apt redis/etcd unavailable.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
runtime_dir="$repo_root/.loop"
env_file="$runtime_dir/runtime.env"
bin_dir="$runtime_dir/bin"
mkdir -p "$runtime_dir" "$bin_dir"

export PATH="/mnt/c/Program Files/Docker/Docker/resources/bin:$PATH"
command -v docker.exe >/dev/null || { echo "docker.exe not found" >&2; exit 1; }
docker() { docker.exe "$@"; }
export -f docker

free_port() { python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()'; }

docker.exe rm -f when-e2e-redis when-e2e-etcd >/dev/null 2>&1 || true

redis_port="$(free_port)"
etcd_port="$(free_port)"

docker.exe pull redis:7-alpine
docker.exe pull quay.io/coreos/etcd:v3.5.16

docker.exe run -d --name when-e2e-redis -p "127.0.0.1:${redis_port}:6379" redis:7-alpine >/dev/null
docker.exe run -d --name when-e2e-etcd \
  -p "127.0.0.1:${etcd_port}:2379" \
  quay.io/coreos/etcd:v3.5.16 \
  /usr/local/bin/etcd \
  --name when-e2e \
  --data-dir /tmp/etcd-data \
  --listen-client-urls http://0.0.0.0:2379 \
  --advertise-client-urls http://0.0.0.0:2379 >/dev/null

cat > "$bin_dir/redis-cli" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
args=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--hostname|--host)
      shift
      [ "$#" -gt 0 ] && shift
      ;;
    -p|--port)
      shift
      [ "$#" -gt 0 ] && shift
      ;;
    *)
      args+=("$1")
      shift
      ;;
  esac
done
exec docker.exe exec when-e2e-redis redis-cli "${args[@]}"
EOF

cat > "$bin_dir/etcdctl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
args=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --endpoints)
      shift
      [ "$#" -gt 0 ] && shift
      ;;
    --endpoints=*)
      shift
      ;;
    *)
      args+=("$1")
      shift
      ;;
  esac
done
exec docker.exe exec when-e2e-etcd etcdctl --endpoints=http://127.0.0.1:2379 "${args[@]}"
EOF
chmod +x "$bin_dir/redis-cli" "$bin_dir/etcdctl"

# Dummy PIDs so stop-deps can no-op kill; containers cleaned by stop-deps-docker
{
  printf 'LOOP_REDIS_PID=%q\n' "1"
  printf 'LOOP_REDIS_PORT=%q\n' "$redis_port"
  printf 'LOOP_REDIS_DIR=%q\n' "$runtime_dir/docker-redis"
  printf 'LOOP_ETCD_PID=%q\n' "1"
  printf 'LOOP_ETCD_PORT=%q\n' "$etcd_port"
  printf 'LOOP_ETCD_DIR=%q\n' "$runtime_dir/docker-etcd"
  printf 'export WHEN_REDIS_HOST=127.0.0.1\n'
  printf 'export WHEN_REDIS_PORT=%q\n' "$redis_port"
  printf 'export WHEN_ETCD_ENDPOINTS=http://127.0.0.1:%q\n' "$etcd_port"
  printf 'export REDIS_URL=redis://127.0.0.1:%q\n' "$redis_port"
  printf 'export ETCD_ENDPOINTS=http://127.0.0.1:%q\n' "$etcd_port"
  printf 'export PATH=%q\n' "$bin_dir:$PATH"
} > "$env_file"

echo "docker dependencies started: redis=$redis_port etcd=$etcd_port"
