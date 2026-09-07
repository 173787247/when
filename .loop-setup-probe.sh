#!/usr/bin/env bash
set -euo pipefail
. /etc/os-release
echo "ID=$ID VERSION=$VERSION_ID"
for c in java javac redis-server redis-cli etcd etcdctl curl python3 perl mvn git; do
  if command -v "$c" >/dev/null 2>&1; then
    echo "$c=$(command -v "$c")"
  else
    echo "$c=MISSING"
  fi
done
java -version 2>&1 | head -1 || true
ls /mnt/c/Users/rchua/tools/jdk-21/bin/java 2>/dev/null || true
ls /mnt/c/Users/rchua/tools/jdk-17/bin/java 2>/dev/null || true
apt-cache policy openjdk-17-jdk-headless redis-server etcd 2>/dev/null | sed -n '1,60p' || true
