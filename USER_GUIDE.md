# When User Guide

When is a delayed-delivery service. Callers submit a message with a future delivery
time and a Sink; When persists it, schedules it on a time wheel, and delivers at
least once to HTTP, Kafka, or a local FILE Sink. Redis holds message state; ETCD
holds cluster metadata; Kafka and observability backends are optional external
dependencies.

This guide matches the OpenAPI contract in `openapi/when-v1.yaml` and operations
verified against a local single-node process (HTTP `28080`, management `18081`).
Placeholder hosts and credentials only — never use production secrets here.

## Quick start

1. Deploy When with external Redis and ETCD (see [DEPLOY.md](DEPLOY.md)).
2. Wait until `GET http://HOST:8081/ready` returns success.
3. Submit a delayed message to `POST http://HOST:8080/api/v1/messages`.
4. Query or cancel with the returned `message_id`.
5. Optionally open the admin web static site and point `/api/` and `/admin/` at When.

## Business HTTP API

Base URL: `http://HOST:8080`. Responses use a common envelope:
`{ "code", "message", "request_id", "data" }`. Pass `X-Request-Id` for correlation
(diagnostic only; not an idempotency key).

### Submit

```bash
curl -sS -X POST "http://127.0.0.1:8080/api/v1/messages" \
  -H "Content-Type: application/json" \
  -H "X-Request-Id: req-example-1" \
  -d '{
    "delay_seconds": 30,
    "sink_type": "HTTP",
    "sink_config": {
      "http": {
        "url": "https://downstream.example.internal/hooks/when",
        "method": "POST",
        "timeout_ms": 5000
      }
    },
    "payload": "eyJhbW91bnQiOjEwMH0=",
    "business_tag": "order-paid"
  }'
```

Provide exactly one of `delay_seconds` or `deliver_at` (epoch millis).
`payload` is Base64. `sink_type` is `HTTP`, `KAFKA`, or `FILE`.

Kafka example Sink:

```json
"sink_type": "KAFKA",
"sink_config": {
  "kafka": {
    "bootstrap_servers": "kafka.example.internal:9092",
    "topic": "when-deliveries",
    "key": "order-1001"
  }
}
```

FILE Sink paths are relative to `WHEN_FILE_SINK_BASE_DIR` on the delivering node.

Successful submit returns `201` with `data.message_id` and `data.status` usually
`PENDING`.

### Query

```bash
curl -sS "http://127.0.0.1:8080/api/v1/messages/{message_id}"
```

Typical status progression: `PENDING` → `DELIVERING` → `DELIVERED` (or
`CANCELLED` / terminal failure after retries). Delivery is at-least-once; downstream
systems must be idempotent.

### Cancel

```bash
curl -sS -X DELETE "http://127.0.0.1:8080/api/v1/messages/{message_id}"
```

Cancel succeeds for `PENDING` messages. Messages already delivering or delivered
may return `409`.

### gRPC

The same Submit / Query / Cancel operations are exposed on the gRPC listener
(`WHEN_GRPC_PORT`, default `9090`) via `when.v1.DelayMessageService`. Prefer HTTP
for external callers unless you already terminate gRPC at the edge.

## Message states and errors

| Status | Meaning |
|---|---|
| `PENDING` | Scheduled, not yet due or not yet claimed for delivery |
| `DELIVERING` | A delivery attempt is in progress |
| `DELIVERED` | Sink reported success |
| `CANCELLED` | Cancelled before successful delivery |

Common error codes include `INVALID_JSON`, `NOT_FOUND`, conflict codes for cancel,
and `503` when the node cannot accept traffic. Admin create-timewheel may return
`CONTROLLER_UNAVAILABLE` when the Controller cannot safely assign Master/Slave
(for example a single-node cluster that cannot place Master and Slave on different
nodes).

OpenAPI documents `Idempotency-Key` for admin time-wheel creation. Business submit
in the published OpenAPI does not declare an HTTP idempotency header; do not assume
duplicate `X-Request-Id` values deduplicate messages.

## Admin API and console

Admin routes live on the **business HTTP port** under `/admin/v1/**` (not the
management port). Management port serves `/health`, `/ready`, and `/metrics` only.

Useful reads:

```bash
curl -sS "http://127.0.0.1:8080/admin/v1/cluster/nodes"
curl -sS "http://127.0.0.1:8080/admin/v1/timewheels"
curl -sS "http://127.0.0.1:8080/admin/v1/messages?limit=50"
curl -sS "http://127.0.0.1:8080/admin/v1/messages/{message_id}"
```

Create time wheels (requires a healthy Controller and enough nodes for Master/Slave
separation):

```bash
curl -sS -X POST "http://127.0.0.1:8080/admin/v1/timewheels" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: create-tw-example-1" \
  -d '{"count":1}'
```

The Vue admin UI is a static site (`when-admin-web` TGZ). Serve `site/` and reverse-proxy
`/admin/` and `/api/` to When on a trusted management network. The MVP admin API has
no authentication; never expose it publicly. Lists intentionally omit payload and
sensitive Sink fields.

## Delivery semantics

- Persistence before acknowledgement: a successful submit means Redis has the message.
- At-least-once: retries and failover can produce duplicate Sink calls.
- HTTP Sink classifies timeouts / 5xx as retryable and many 4xx as terminal per Sink rules.
- Kafka Sink requires `WHEN_KAFKA_BOOTSTRAP_SERVERS` (or equivalent config) on the node.

## Observability and troubleshooting

| Signal | Where |
|---|---|
| Liveness | `GET http://HOST:8081/health` |
| Readiness | `GET http://HOST:8081/ready` |
| Metrics | `GET http://HOST:8081/metrics` (Prometheus) |
| Logs | JSON logs with `trace_id`, `message_id`, `node_id` |
| Traces | Export via `OTEL_EXPORTER_OTLP_ENDPOINT` to your Collector |

Correlate a failed delivery with: Request ID from the API response → `message_id` →
admin message details (attempts) → metrics `when_delivery_*` → Trace/log `trace_id`.

If `/ready` fails, fix Redis/ETCD connectivity or node registration before restarting
in a tight loop. Worker IDs must be unique per live node.
