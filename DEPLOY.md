# Deploying When

When is distributed as a server TGZ, an independent admin-web TGZ, a container
image, and a Kubernetes Kustomize base. Every form contains only When. Redis,
ETCD, Kafka, HTTP downstreams, and observability backends must already exist and
remain the deployment operator's responsibility.

The MVP admin API has no authentication. Keep port 8081 and the admin web UI on
a trusted management network.

## Prerequisites and configuration

Required runtime values are `WHEN_NODE_ID`, `WHEN_WORKER_ID` (0-1023),
`WHEN_ETCD_ENDPOINTS`, and `WHEN_REDIS_HOST`. ETCD endpoints must be absolute
HTTP(S) URIs and must not contain credentials. A live cluster must assign a
unique node ID and worker ID to every node.

Common optional values are:

| Variable | Default | Purpose |
|---|---:|---|
| `WHEN_HTTP_PORT` | `8080` | Public HTTP API |
| `WHEN_GRPC_PORT` | `9090` | Peer-to-peer gRPC listener |
| `WHEN_NODE_HOST` | `127.0.0.1` | Address advertised to peer nodes |
| `WHEN_MANAGEMENT_HOST` | `127.0.0.1` | Management listener bind address |
| `WHEN_MANAGEMENT_PORT` | `8081` | Health, readiness, metrics, and admin |
| `WHEN_REDIS_PORT` | `6379` | External Redis port |
| `WHEN_KAFKA_BOOTSTRAP_SERVERS` | empty | Required only for Kafka delivery |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | empty | Optional external OTLP Collector |
| `WHEN_LOG_LEVEL` | `INFO` | Root log level |
| `WHEN_LOG_FORMAT` | `json` | Structured logging format |
| `WHEN_SHUTDOWN_TIMEOUT` | `30s` | Graceful shutdown budget |

Set `WHEN_ETCD_USERNAME`, `WHEN_ETCD_PASSWORD`, and `WHEN_REDIS_PASSWORD` only
through the process environment, a Kubernetes Secret, or a platform secret
manager. Never put them in Git, a ConfigMap, an image layer, command-line
arguments, or logs. See [deploy/examples/when.env](deploy/examples/when.env) and
[deploy/examples/application.yml](deploy/examples/application.yml) for the
shared non-secret contract. The YAML file is a human-readable reference; this
release reads authoritative values from the environment.

Readiness is served at `http://HOST:8081/ready`. It becomes successful only
after initialization, Redis and ETCD connectivity, node registration, and local
role recovery. `/health` indicates that the process is alive and `/metrics`
exposes Prometheus metrics.

## TGZ deployment

Build and inspect the release:

```bash
make release VERSION=1.0.0
(cd dist && shasum -a 256 -c checksums.txt)
tar -xzf dist/when-server-1.0.0.tgz
```

Export the required environment variables, then run in the foreground under a
service manager:

```bash
cd when-server-1.0.0
bin/when run
```

For a traditional host, `bin/when start`, `bin/when status`, and `bin/when stop`
manage only the current When process and its PID file under `var/`. The optional
`--config conf/application.example.yml` argument validates that the reference
file exists; it does not override environment values.

The admin-web archive contains static files under `site/`. Serve that directory
separately and configure the trusted reverse proxy to send `/api/` and
`/admin/` requests to When.

## Container deployment

Build a versioned image (the Make target does not create a `latest` tag):

```bash
make image VERSION=1.0.0 IMAGE_REPO=registry.example.com/when
docker run --rm --name when-1 --env-file ./when.local.env \
  -p 8080:8080 -p 8081:8081 -p 9090:9090 \
  registry.example.com/when:1.0.0
```

The final image contains a JRE and When Server only, runs as UID/GID 10001, and
starts `bin/when run`. For a read-only root filesystem, mount writable temporary
storage at `/tmp` and, only if process-management files are needed, at
`/opt/when/var`. Set `WHEN_MANAGEMENT_HOST=0.0.0.0` when the management listener
must be reached through a published container port, and restrict that port to a
trusted management network.

## Kubernetes deployment

The base creates one three-replica When StatefulSet, headless/API/management
Services, a ConfigMap, ServiceAccount, and PodDisruptionBudget. It references a
pre-created Secret named `when-external-credentials`; it does not create any
Secret or external dependency workload.

Before applying it:

1. Mirror the versioned image to the deployment registry and update the image
   in `deploy/k8s/base/statefulset.yaml` or with a Kustomize overlay.
2. Replace the documentation-only ETCD and Redis hosts in `when-config` through
   an overlay.
3. If authentication is enabled, have the platform secret manager create
   `when-external-credentials` with the keys `etcd-username`, `etcd-password`,
   and `redis-password`.
4. Restrict `when-management` with the platform's network policy or internal
   load-balancer controls.

Validate and deploy:

```bash
make k8s-validate
kubectl -n when apply -k deploy/k8s/base
kubectl -n when rollout status statefulset/when --timeout=300s
kubectl -n when get pods,svc
```

The Pod name supplies `WHEN_NODE_ID`. If `WHEN_WORKER_ID` is absent, the bundled
launcher derives it from the StatefulSet ordinal; an explicitly supplied value
takes precedence. Pods use a read-only root filesystem, drop all Linux
capabilities, disable privilege escalation, and mount writable empty directories
only for `/tmp` and `/opt/when/var`.

## Upgrade and rollback

Build immutable, versioned artifacts and verify `checksums.txt` before rollout.
For Kubernetes, update only the image tag/digest and let the StatefulSet replace
one ready Pod at a time; the PDB keeps at least two available. Monitor `/ready`,
Controller election, replica synchronization, delivery lag, and Sink failures.

To roll back, restore the previous image tag/digest or reinstall the previous
verified TGZ, then restart one node at a time. Do not roll back Redis or ETCD
metadata independently. Schema compatibility and mixed-version operation beyond
a single rolling transition require a separately validated release plan.

## Troubleshooting

- A startup error naming `WHEN_*` means a required value is absent or malformed.
- `/health` succeeds but `/ready` fails when Redis/ETCD is unreachable, node
  registration is invalid, or role recovery is incomplete. Repair the external
  dependency or configuration; avoid restart loops.
- Worker-ID conflicts require assigning a unique value; never reuse a live
  node's worker ID.
- Kafka failures matter only when a Kafka Sink is configured. Confirm the
  external broker address and network policy without printing credentials.
- Inspect structured logs by `trace_id`, `message_id`, and operation. Logs and
  management APIs intentionally omit payload and sensitive Sink configuration.
- A checksum failure means the artifact must not be deployed; rebuild it from
  the intended source revision.
