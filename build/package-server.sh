#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  printf 'usage: %s VERSION DIST_DIR\n' "$0" >&2
  exit 64
fi

version="$1"
dist_dir="$2"
if [[ ! "$version" =~ ^[0-9A-Za-z][0-9A-Za-z._-]*$ ]]; then
  printf 'invalid release version: %s\n' "$version" >&2
  exit 64
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/when-server-package.XXXXXX")"
cleanup() {
  rm -rf -- "$work_dir"
}
trap cleanup EXIT INT TERM

release_name="when-server-$version"
release_root="$work_dir/$release_name"
mkdir -p "$dist_dir/sbom" "$release_root/bin" "$release_root/lib" \
  "$release_root/conf" "$release_root/licenses"

shopt -s nullglob
runner_jars=("$repo_root"/when-app/target/when-app-*-runner.jar)
shopt -u nullglob
if [[ ${#runner_jars[@]} -ne 1 ]]; then
  printf 'expected exactly one When runner JAR, found %s\n' "${#runner_jars[@]}" >&2
  exit 1
fi

install -m 0755 "$repo_root/build/server/bin/when" "$release_root/bin/when"
python3 "$repo_root/build/normalize-jar.py" \
  "${runner_jars[0]}" "$release_root/lib/when-server.jar"
chmod 0644 "$release_root/lib/when-server.jar"
install -m 0644 "$repo_root/build/server/conf/application.example.yml" \
  "$release_root/conf/application.example.yml"
install -m 0644 "$repo_root/build/server/conf/logback.xml" "$release_root/conf/logback.xml"
install -m 0644 "$repo_root/LICENSE" "$release_root/licenses/LICENSE"
sed "s/__VERSION__/$version/g" "$repo_root/build/server/README.md" > "$release_root/README.md"
printf '%s\n' "$version" > "$release_root/VERSION"
chmod 0644 "$release_root/README.md" "$release_root/VERSION"

find "$release_root" -exec touch -h -t 200001010000 {} +
archive_tmp="$dist_dir/.$release_name.tar"
(
  cd "$work_dir"
  COPYFILE_DISABLE=1 find "$release_name" -print | LC_ALL=C sort \
    | COPYFILE_DISABLE=1 tar --no-xattrs -cnf "$archive_tmp" -T -
)
gzip -n -c "$archive_tmp" > "$dist_dir/$release_name.tgz"
rm -f -- "$archive_tmp"

cat > "$dist_dir/sbom/$release_name.cdx.json" <<EOF
{
  "bomFormat": "CycloneDX",
  "specVersion": "1.5",
  "version": 1,
  "metadata": {
    "component": {
      "type": "application",
      "name": "when-server",
      "version": "$version"
    }
  },
  "components": []
}
EOF
