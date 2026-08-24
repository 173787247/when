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
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/when-web-package.XXXXXX")"
cleanup() {
  rm -rf -- "$work_dir"
}
trap cleanup EXIT INT TERM

release_name="when-admin-web-$version"
release_root="$work_dir/$release_name"
mkdir -p "$dist_dir/sbom" "$release_root/licenses" "$release_root/site"
"$repo_root/build/build-web.sh" "$release_root/site"
install -m 0644 "$repo_root/LICENSE" "$release_root/licenses/LICENSE"
sed "s/__VERSION__/$version/g" "$repo_root/build/web-README.md" > "$release_root/README.md"
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
      "name": "when-admin-web",
      "version": "$version"
    }
  },
  "components": []
}
EOF
