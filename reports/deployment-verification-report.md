# Deployment Verification Report (Lesson 53 follow-along)

- Date: 2026-09-07
- Artifacts from lesson52 packaging on this machine

## TGZ

| File | Size (bytes) | SHA-256 |
|---|---:|---|
| `dist/when-server-1.0.0-lesson52.tgz` | 70001812 | `DB19A7CFA0B7CFA1976A14465CAE02D82A99500CA91BEFEB819B836CE9DDADD4` |
| `dist/when-admin-web-1.0.0-lesson52.tgz` | 389355 | `6DBCA88C7E56484E321D340B2BB6C0A8AAFB11905E5E7F5281FAA3F9B3567164` |

Extract smoke (lesson52): `bin/when`, runner jar, and admin-web `index` present.
`check-release.sh` official duplicate-entry check **FAIL** on MSYS tar listing (content still usable).

TGZ does not bundle Redis, ETCD, Kafka, or observability binaries.

## OCI image

- Tag: `whenproject/when:1.0.0-lesson52`
- Image id: `sha256:cfd824894a4bcd72626e52fd97baa8e88a5bd3afa45c3cb81d0ed1a93f80ed6b`
- User: `10001:10001`
- Entrypoint: `["/opt/when/bin/when","run"]`
- Build: official root `Dockerfile` with `--no-cache` after CRLF/`openapi`/`when-sink-file` fixes
- Evidence: `.loop/lesson52-docker-official.log`

Credentials are not baked into the image; external addresses come from env / mounts.

## Kubernetes

- `kubectl kustomize deploy/k8s/base` → PASS (lesson52)
- Base contains When StatefulSet/Services/ConfigMap/PDB/security context only
- Live `kubectl apply` + three Pod Ready + delete-Pod recovery: **not executed** (no prepared When namespace / three-node env on this host)

## Official artifact gate

`harness/release/verify-artifacts.sh` is missing. Manual substitute: TGZ hashes, image inspect, kustomize.

## Docs

- `DEPLOY.md` present (calibrated notes for admin port / Windows follow-along)
- `USER_GUIDE.md` added this session
