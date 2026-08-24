# When observability stack

This directory configures an observability stack that is deployed independently from When. It is
not included in the When server artifact. Point each When node at the collector with
`OTEL_EXPORTER_OTLP_ENDPOINT`; Prometheus scrapes the node's management listener, and the platform
log collector forwards the JSON stdout stream to Loki.

Components and default development ports:

- When management: `127.0.0.1:8081` (`/metrics`, `/health`, `/ready`)
- OpenTelemetry Collector: OTLP gRPC `4317`, OTLP HTTP `4318`
- Prometheus: `9090`
- Loki: `3100`
- Tempo: `3200` query API and `4317` OTLP ingest
- Grafana: `3000`

The repository intentionally contains no Docker Compose setup. Start these services through the
organization's observability platform or their normal standalone packages, passing these files as
their configuration. Update the Prometheus targets and collector/Tempo host names for that
environment. No credential belongs in these files; inject any authentication through the
platform's secret mechanism.

Grafana provisioning connects metrics exemplars to Tempo and derives a Tempo link from the
`trace_id` field in Loki. The Tempo datasource likewise links a trace to Loki logs with the same
trace ID.
