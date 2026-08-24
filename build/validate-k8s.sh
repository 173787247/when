#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
rendered="$(mktemp "${TMPDIR:-/tmp}/when-k8s.XXXXXX")"
cleanup() {
  rm -f -- "$rendered"
}
trap cleanup EXIT INT TERM

kubectl kustomize "$repo_root/deploy/k8s/base" > "$rendered"

if grep -Eq '^kind: (Secret|Deployment|DaemonSet|Job|CronJob)$' "$rendered"; then
  printf 'Kubernetes base contains a resource outside the When deployment boundary\n' >&2
  exit 1
fi
if ! grep -q '^kind: StatefulSet$' "$rendered"; then
  printf 'Kubernetes base must contain the When StatefulSet\n' >&2
  exit 1
fi
if grep -Eiq '^  name: (redis|etcd|kafka|prometheus|loki|tempo|grafana)' "$rendered"; then
  printf 'Kubernetes base must not create external dependency resources\n' >&2
  exit 1
fi

printf 'Kubernetes manifests are valid and contain only When resources\n'
