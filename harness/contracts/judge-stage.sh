#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
stage="${1:?stage required}"
cd "$repo_root"
python3 harness/contracts/verify_contracts.py --stage "$stage"
case "$stage" in
  lesson39) ./mvnw -q verify ;;
  lesson40) ./mvnw -q -pl when-api -am verify ;;
  lesson41) ./mvnw -q -pl when-storage-redis -am verify ;;
  lesson42|lesson43) ./mvnw -q -pl when-cluster -am verify ;;
  lesson44) ./mvnw -q -pl when-timewheel -am verify ;;
  lesson45) ./mvnw -q -pl when-acceptance -Pacceptance verify ;;
  *) echo "unknown stage: $stage" >&2; exit 2 ;;
esac
