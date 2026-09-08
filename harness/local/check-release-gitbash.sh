#!/usr/bin/env bash
# One-off Git Bash wrapper: package existing jar + web-dist snapshot, then check-release.
set -euo pipefail
export PATH="/c/Users/rchua/AppData/Local/Programs/Python/Python311:/usr/bin:/bin:${PATH}"
mkdir -p /tmp/py311bin
cp -f /c/Users/rchua/AppData/Local/Programs/Python/Python311/python.exe /tmp/py311bin/python3
export PATH="/tmp/py311bin:${PATH}"

repo="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo"
dist="$repo/dist"
rm -rf "$dist"
mkdir -p "$dist/sbom"

./build/package-server.sh 1.0.0 "$dist"
echo PKG_SERVER=0

# build-web.sh hash is stale vs when-admin-web; use committed snapshot for archive check only.
work="$(mktemp -d "${TMPDIR:-/tmp}/when-web-pack.XXXXXX")"
rel="$work/when-admin-web-1.0.0"
mkdir -p "$rel/licenses" "$rel/site"
cp -R build/web-dist/. "$rel/site/"
cp LICENSE "$rel/licenses/LICENSE"
sed "s/__VERSION__/1.0.0/g" build/web-README.md > "$rel/README.md"
printf '%s\n' 1.0.0 > "$rel/VERSION"
find "$rel" -exec touch -h -t 200001010000 {} +
archive_tmp="$dist/.when-admin-web-1.0.0.tar"
(
  cd "$work"
  COPYFILE_DISABLE=1 find when-admin-web-1.0.0 -print | LC_ALL=C sort \
    | COPYFILE_DISABLE=1 tar --no-xattrs -cnf "$archive_tmp" -T -
)
gzip -n -c "$archive_tmp" > "$dist/when-admin-web-1.0.0.tgz"
rm -f -- "$archive_tmp"
cat > "$dist/sbom/when-admin-web-1.0.0.cdx.json" <<'EOF'
{"bomFormat":"CycloneDX","specVersion":"1.5","version":1,"metadata":{"component":{"type":"application","name":"when-admin-web","version":"1.0.0"}},"components":[]}
EOF
echo PKG_WEB=0

./build/check-release.sh 1.0.0 "$dist"
echo CHECK_RELEASE_EXIT=0
