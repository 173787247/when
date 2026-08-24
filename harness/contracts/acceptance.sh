#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"
python3 harness/contracts/verify_contracts.py --stage lesson45 --final
./mvnw -q -pl when-acceptance -Pacceptance verify
