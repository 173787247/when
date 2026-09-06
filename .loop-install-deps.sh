#!/usr/bin/env bash
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
echo "== apt update =="
sudo apt-get update -y
echo "== install =="
sudo apt-get install -y openjdk-17-jdk-headless redis-server etcd curl
echo "== verify =="
java -version
redis-server --version
etcd --version | head -3
etcdctl version | head -3
echo SETUP_OK
