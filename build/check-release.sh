#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  printf 'usage: %s VERSION DIST_DIR\n' "$0" >&2
  exit 64
fi

version="$1"
dist_dir="$2"
server_archive="$dist_dir/when-server-$version.tgz"
web_archive="$dist_dir/when-admin-web-$version.tgz"
server_sbom="$dist_dir/sbom/when-server-$version.cdx.json"
web_sbom="$dist_dir/sbom/when-admin-web-$version.cdx.json"

for required in "$server_archive" "$web_archive" "$server_sbom" "$web_sbom"; do
  if [[ ! -s "$required" ]]; then
    printf 'missing or empty release file: %s\n' "$required" >&2
    exit 1
  fi
done

for archive in "$server_archive" "$web_archive"; do
  if tar -tzf "$archive" | LC_ALL=C sort | uniq -d | grep -q .; then
    printf 'release archive contains duplicate entries: %s\n' "$archive" >&2
    exit 1
  fi
done

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/when-release-check.XXXXXX")"
cleanup() {
  rm -rf -- "$work_dir"
}
trap cleanup EXIT INT TERM
tar -xzf "$server_archive" -C "$work_dir"
tar -xzf "$web_archive" -C "$work_dir"

server_root="$work_dir/when-server-$version"
web_root="$work_dir/when-admin-web-$version"
test -x "$server_root/bin/when"
test -s "$server_root/lib/when-server.jar"
test -s "$server_root/conf/application.example.yml"
test -s "$server_root/conf/logback.xml"
test -s "$server_root/licenses/LICENSE"
test "$(tr -d '\r\n' < "$server_root/VERSION")" = "$version"
test -s "$web_root/site/index.html"
test -s "$web_root/licenses/LICENSE"
test "$(tr -d '\r\n' < "$web_root/VERSION")" = "$version"

if find "$server_root" "$web_root" -type f \( \
    -name 'docker-compose*.yml' -o -name 'docker-compose*.yaml' -o \
    -name 'redis-server' -o -name 'etcd' -o -name 'kafka-server-start.sh' -o \
    -name 'prometheus' -o -name 'grafana-server' \) -print -quit | grep -q .; then
  printf 'release contains an external-service executable or compose file\n' >&2
  exit 1
fi

(
  cd "$dist_dir"
  {
    shasum -a 256 "when-server-$version.tgz"
    shasum -a 256 "when-admin-web-$version.tgz"
    shasum -a 256 "sbom/when-server-$version.cdx.json"
    shasum -a 256 "sbom/when-admin-web-$version.cdx.json"
  } | LC_ALL=C sort -k2 > checksums.txt
  shasum -a 256 -c checksums.txt
)

printf 'release artifacts verified for version %s\n' "$version"
