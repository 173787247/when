#!/usr/bin/env bash
set -euo pipefail
export PATH="/mnt/c/Program Files/Docker/Docker/resources/bin:$PATH"
docker.exe rm -f when-e2e-redis when-e2e-etcd >/dev/null 2>&1 || true
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
rm -f "$repo_root/.loop/runtime.env"
echo "docker dependencies stopped"
