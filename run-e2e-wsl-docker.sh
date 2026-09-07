#!/usr/bin/env bash
# Run WHEN single-node E2E on WSL+Docker (Windows host).
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$repo_root"

export JAVA_HOME="${JAVA_HOME:-/mnt/c/Users/rchua/tools/jdk-21}"
DOCKER_BIN="/mnt/c/Program Files/Docker/Docker/resources/bin"
mkdir -p "$repo_root/.loop/bin"
cat > "$repo_root/.loop/bin/java" <<'EOF'
#!/usr/bin/env bash
exec /mnt/c/Users/rchua/tools/jdk-21/bin/java.exe "$@"
EOF
cat > "$repo_root/.loop/bin/docker" <<'EOF'
#!/usr/bin/env bash
exec "/mnt/c/Program Files/Docker/Docker/resources/bin/docker.exe" "$@"
EOF
chmod +x "$repo_root/.loop/bin/java" "$repo_root/.loop/bin/docker"
# Prefer our wrappers over WSL docker stub
export PATH="$repo_root/.loop/bin:$DOCKER_BIN:$PATH"

java -version
docker version >/dev/null
echo "java+docker ok"

sed -i 's/\r$//' harness/local/start-deps-docker.sh harness/local/stop-deps-docker.sh harness/local/wait-deps.sh harness/core-tests/*.sh || true
chmod +x harness/local/start-deps-docker.sh harness/local/stop-deps-docker.sh

cleanup() {
  harness/core-tests/stop-single-node.sh 2>/dev/null || true
  harness/local/stop-deps-docker.sh 2>/dev/null || true
}
trap cleanup EXIT

harness/local/stop-deps-docker.sh || true
harness/local/start-deps-docker.sh
# shellcheck disable=SC1091
source .loop/runtime.env
export PATH="$repo_root/.loop/bin:$PATH"

echo "waiting for redis/etcd..."
deadline=$((SECONDS + 90))
while :; do
  ok=1
  redis-cli ping 2>/dev/null | grep -qx PONG || ok=0
  # Prefer Windows curl.exe: WSL localhost to published Docker ports can fail
  if command -v curl.exe >/dev/null 2>&1; then
    curl.exe -fsS "http://127.0.0.1:$LOOP_ETCD_PORT/health" >/dev/null 2>&1 || ok=0
  else
    curl -fsS "http://127.0.0.1:$LOOP_ETCD_PORT/health" >/dev/null 2>&1 || ok=0
  fi
  [ "$ok" = 1 ] && { echo "local dependencies ready"; break; }
  [ "$SECONDS" -lt "$deadline" ] || { echo "dependencies did not become ready within 90s" >&2; exit 1; }
  sleep 1
done

# Ensure jars (use repo mvnw; may download deps)
if [ ! -f when-app/target/when-app-1.0.0-SNAPSHOT-runner.jar ] || [ ! -f when-e2e-test/target/when-e2e-test-1.0.0-SNAPSHOT-runner.jar ]; then
  ./mvnw -q -pl when-app,when-e2e-test -am package -DskipTests
fi

harness/core-tests/start-single-node.sh
# shellcheck disable=SC1091
source .loop/single-node.env
export PATH="$repo_root/.loop/bin:$PATH"
harness/core-tests/run-single-node-flow.sh
