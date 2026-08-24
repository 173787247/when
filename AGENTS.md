# When Loop Harness

This repository is a staged implementation of **When**, a privately deployable distributed delayed-delivery component. Follow the project documents in `docs/` as the source of truth. The precedence for implementation decisions is: requirements, technical design, AI implementation guide, lesson 39 common contract, current lesson material, then Loop-runner rules.

## Workflow and scope

- The official first-run stages are `lesson39` through `lesson45`, in that order. Work only in the active `lesson/<n>` branch. Each lesson starts from current `master`, passes its external judge, commits, and is merged with `--no-ff` before the next lesson starts.
- Do not use Git worktrees, Docker, Docker Compose, Testcontainers, remote pushes, production credentials, or `--full-auto`.
- Read the current lesson, the common contract, relevant upstream handoffs, and failure evidence before changing code. Fix ordinary implementation, compile, and test failures in the same lesson branch without asking for confirmation.
- Do not modify `AGENTS.md`, `docs/**`, `harness/contracts/**`, `harness/loop/schema/**`, `harness/loop/protected-paths.txt`, or CI/acceptance test inputs to make a check pass. The Runner checks real Git changes independently.
- Keep changes within the active stage's configured write paths. Do not implement future lessons early.

## Architecture and common contract

- Production code is Java. External callers use HTTP; nodes use gRPC internally. `when-common` owns `Message`, `MessageStatus`, sink types/configuration, and public SPI/proto definitions; modules consume these contracts rather than redefining them.
- A message is durable only after Redis persistence succeeds. Redis is the authority for message facts; ETCD is the authority for node, Controller, and time-wheel metadata; memory time wheels are rebuildable indexes only. ETCD never stores payload.
- Legal states are `PENDING -> DELIVERING -> DELIVERED`, `PENDING -> CANCELLED`, `DELIVERING -> PENDING` for a retry, and `DELIVERING -> FAILED`. State transitions use atomic conditional storage operations.
- Preserve at-least-once delivery and the `message_id` idempotency contract. Do not claim exactly-once delivery.
- Every time wheel has a stable `tw_id`; Master/Slave must be on different nodes. Controller decisions are idempotent and replayable. The first run uses static distribution only; dynamic failover/rebalance and real HTTP/Kafka Sinks belong to later lessons.
- The timer callback must not perform synchronous Redis, network, or Sink I/O. It only hands a due message to an asynchronous `DueMessageHandler`.

## Security, operations, and testing

- Never hard-code or log credentials, tokens, connection-string credentials, payloads, or sensitive Sink configuration. Read configuration from documented environment variables.
- Do not expose payloads or sensitive Sink configuration through errors, logs, metrics, or management APIs. `message_id`, `trace_id`, `tw_id`, URLs, topics, and exception text are not metric labels.
- Redis uses individual message and schedule-index keys with TTL; do not aggregate all messages in a global ZSet or Hash. ETCD endpoints and Redis settings come from the environment.
- Use the Harness-managed local Redis and ETCD processes for integration checks. Do not start containers. Stop dependencies after every judge.
- Automated acceptance is authoritative. Do not weaken, delete, skip, or rewrite tests. Record simple implementation choices and objective remaining risks in the Runner report.

## Lesson boundaries

- Lesson 39: Maven skeleton, common models, SPI, and common proto only.
- Lesson 40: API proto and thin gRPC delegation/validation only.
- Lesson 41: Redis `StoragePlugin` only.
- Lesson 42: ETCD metadata client/key contract only.
- Lesson 43: membership, heartbeat, Controller election, watches, and read-only cluster view only.
- Lesson 44: Netty `HashedWheelTimer` wrapper, isolation, cancellation, and rebuild only.
- Lesson 45: ingress validation, IDs, stable routing, forwarding, persistence-before-scheduling, first-run wiring, and first-stage acceptance only.
