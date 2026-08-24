#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output_dir="${1:-}"
expected_hash="$(tr -d '\r\n' < "$repo_root/build/web-dist/SOURCE_SHA256")"
actual_hash="$(
  cd "$repo_root"
  find when-admin-web -type f -not -path '*/target/*' -print \
    | LC_ALL=C sort \
    | xargs shasum -a 256 \
    | shasum -a 256 \
    | awk '{print $1}'
)"
if [[ "$actual_hash" != "$expected_hash" ]]; then
  printf 'prebuilt admin web is stale; expected source hash %s, got %s\n' \
    "$expected_hash" "$actual_hash" >&2
  exit 1
fi
test -s "$repo_root/build/web-dist/index.html"

if [[ -n "$output_dir" ]]; then
  mkdir -p "$output_dir"
  cp -R "$repo_root/build/web-dist/." "$output_dir/"
fi

printf 'admin web snapshot verified (%s)\n' "$actual_hash"
