package com.when.delivery;

import com.when.core.DeliveryResult;
import com.when.core.DueMessageHandler;
import com.when.core.HttpSinkConfig;
import com.when.core.KafkaSinkConfig;
import com.when.core.Message;
import com.when.core.MessageStatus;
import com.when.core.Sink;
import com.when.core.SinkType;
import com.when.core.StatePatch;
import com.when.core.StoragePlugin;
import com.when.core.TimeWheelRegistry;
import com.when.sink.spi.AttemptAwareSink;
import com.when.sink.spi.SinkRegistry;
import com.when.observability.ErrorCode;
import com.when.observability.LogEvent;
import com.when.observability.Metrics;
import com.when.observability.StructuredEventLogger;
import com.when.observability.TraceAttributes;
import com.when.observability.TraceContextSnapshot;
import com.when.observability.TraceContextStore;
import com.when.observability.TraceLink;
import com.when.observability.TraceOperations;
import com.when.observability.TraceSpanKind;
import com.when.observability.WhenMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.net.URI;
import java.util.Map;
import java.util.Objects;

/** Production due-message orchestration: claim, deliver, persist outcome, and reschedule. */
public final class DefaultDueMessageHandler implements DueMessageHandler {
    public static final Duration DEFAULT_LEASE_DURATION = Duration.ofMinutes(1);
    private static final int MAX_ERROR_LENGTH = 512;

    private final String nodeId;
    private final StoragePlugin storage;
    private final SinkRegistry sinks;
    private final RetryPolicy retryPolicy;
    private final TimeWheelRegistry timeWheels;
    private final DeliveryStateStore deliveryState;
    private final Clock clock;
    private final Duration leaseDuration;
    private final Metrics metrics;
    private final TraceOperations traces;
    private final TraceContextStore traceContexts;
    private final StructuredEventLogger events;

    public DefaultDueMessageHandler(
            StoragePlugin storage,
            SinkRegistry sinks,
            RetryPolicy retryPolicy,
            TimeWheelRegistry timeWheels) {
        this(
                "local-node",
                storage,
                sinks,
                retryPolicy,
                timeWheels,
                new InMemoryDeliveryStateStore(),
                Clock.systemUTC(),
                DEFAULT_LEASE_DURATION,
                Metrics.noop(),
                TraceOperations.noop(),
                TraceContextStore.noop(),
                new StructuredEventLogger(DefaultDueMessageHandler.class, "when", "local-node"));
    }

    public DefaultDueMessageHandler(
            StoragePlugin storage,
            Map<SinkType, ? extends Sink> sinks,
            RetryPolicy retryPolicy,
            TimeWheelRegistry timeWheels) {
        this(storage, new SinkRegistry(sinks.values()), retryPolicy, timeWheels);
    }

    public DefaultDueMessageHandler(
            String nodeId,
            StoragePlugin storage,
            SinkRegistry sinks,
            RetryPolicy retryPolicy,
            TimeWheelRegistry timeWheels,
            DeliveryStateStore deliveryState,
            Clock clock,
            Duration leaseDuration) {
        this(
                nodeId,
                storage,
                sinks,
                retryPolicy,
                timeWheels,
                deliveryState,
                clock,
                leaseDuration,
                Metrics.noop(),
                TraceOperations.noop(),
                TraceContextStore.noop(),
                new StructuredEventLogger(DefaultDueMessageHandler.class, "when", nodeId));
    }

    public DefaultDueMessageHandler(
            String nodeId,
            StoragePlugin storage,
            SinkRegistry sinks,
            RetryPolicy retryPolicy,
            TimeWheelRegistry timeWheels,
            DeliveryStateStore deliveryState,
            Clock clock,
            Duration leaseDuration,
            Metrics metrics,
            TraceOperations traces,
            TraceContextStore traceContexts,
            StructuredEventLogger events) {
        this.nodeId = requireText(nodeId, "nodeId");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.sinks = Objects.requireNonNull(sinks, "sinks");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.timeWheels = Objects.requireNonNull(timeWheels, "timeWheels");
        this.deliveryState = Objects.requireNonNull(deliveryState, "deliveryState");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseDuration = requirePositive(leaseDuration, "leaseDuration");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.traces = Objects.requireNonNull(traces, "traces");
        this.traceContexts = Objects.requireNonNull(traceContexts, "traceContexts");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public void onDue(String messageId) {
        Message message = storage.get(requireText(messageId, "messageId")).orElse(null);
        if (message == null || message.status() != MessageStatus.PENDING) {
            return;
        }
        TraceLink link = originLink(messageId);
        traces.inLinkedSpan(
                "when.deliver",
                TraceSpanKind.INTERNAL,
                TraceAttributes.of(
                        "when.sink.type", message.sinkType().name().toLowerCase(java.util.Locale.ROOT),
                        "when.delivery.attempt", Integer.toString(message.retryCount() + 1)),
                link,
                () -> {
                    deliverPending(message);
                    return null;
                });
    }

    private void deliverPending(Message message) {
        String messageId = message.messageId();

        Instant leaseUntil = clock.instant().plus(effectiveLeaseDuration(message));
        DeliveryLease lease = deliveryState.tryAcquire(messageId, nodeId, leaseUntil).orElse(null);
        if (lease == null) {
            return;
        }
        if (!storage.transition(
                messageId,
                MessageStatus.PENDING,
                MessageStatus.DELIVERING,
                new StatePatch(null, null, null, null))) {
            deliveryState.complete(
                    messageId,
                    lease.attemptId(),
                    DeliveryResult.permanentFailure("CLAIM_REJECTED", "message is not pending", 0));
            return;
        }

        Instant startedAt = clock.instant();
        DeliveryResult result = deliver(message, lease.attemptId());
        Instant endedAt = clock.instant();
        deliveryState.appendAttempt(DeliveryAttempt.from(
                messageId, lease.attemptId(), startedAt, endedAt, result));

        if (result.success()) {
            if (storage.transition(
                    messageId,
                    MessageStatus.DELIVERING,
                    MessageStatus.DELIVERED,
                    new StatePatch(null, null, clock.millis(), null))) {
                deliveryState.complete(messageId, lease.attemptId(), result);
                recordDelivery(message, result, "success");
                deleteTraceContext(messageId);
            }
            return;
        }

        recordDelivery(message, result, "failure");

        String lastError = safeError(result);
        if (retryPolicy.shouldRetry(result, message.retryCount())) {
            int nextRetryCount = message.retryCount() + 1;
            long nextAttemptAt = safeAdd(
                    clock.millis(), retryPolicy.nextDelay(nextRetryCount).toMillis());
            boolean returnedToPending = storage.transition(
                    messageId,
                    MessageStatus.DELIVERING,
                    MessageStatus.PENDING,
                    new StatePatch(nextRetryCount, nextAttemptAt, null, lastError));
            if (returnedToPending) {
                Message pending = storage.get(messageId).orElse(null);
                if (pending != null && pending.status() == MessageStatus.PENDING) {
                    timeWheels.require(pending.timeWheelId()).add(pending);
                    deliveryState.complete(messageId, lease.attemptId(), result);
                }
            }
            return;
        }

        if (storage.transition(
                messageId,
                MessageStatus.DELIVERING,
                MessageStatus.FAILED,
                new StatePatch(null, null, null, lastError))) {
            deliveryState.complete(messageId, lease.attemptId(), result);
        }
    }

    private TraceLink originLink(String messageId) {
        try {
            TraceContextSnapshot context = traceContexts.get(messageId).orElse(null);
            return context == null
                    ? TraceLink.invalid()
                    : traces.parseLink(context.traceparent(), context.tracestate());
        } catch (RuntimeException failure) {
            events.warn(
                    LogEvent.TRACE_EXPORT_FAILED,
                    ErrorCode.EXPORT_FAILED,
                    "delivery trace context could not be loaded",
                    Map.of("message_id", messageId, "operation", "trace_context_get"));
            return TraceLink.invalid();
        }
    }

    private void deleteTraceContext(String messageId) {
        try {
            traceContexts.delete(messageId);
        } catch (RuntimeException failure) {
            events.warn(
                    LogEvent.TRACE_EXPORT_FAILED,
                    ErrorCode.EXPORT_FAILED,
                    "completed delivery trace context could not be removed",
                    Map.of("message_id", messageId, "operation", "trace_context_delete"));
        }
    }

    private void recordDelivery(Message message, DeliveryResult result, String status) {
        String sinkType = message.sinkType().name().toLowerCase(java.util.Locale.ROOT);
        metrics.incr(WhenMetrics.SINK_DELIVERIES, "sink_type", sinkType, "result", status);
        metrics.observe(
                WhenMetrics.SINK_DELIVERY_DURATION,
                Math.max(0d, result.durationMs() / 1_000d),
                "sink_type", sinkType);
        metrics.observe(
                WhenMetrics.DELIVERY_LAG,
                Math.max(0d, (clock.millis() - message.deliverAt()) / 1_000d),
                "sink_type", sinkType);
        Map<String, Object> fields = Map.of(
                "message_id", message.messageId(),
                "tw_id", message.timeWheelId(),
                "sink_type", message.sinkType().name(),
                "duration_ms", result.durationMs(),
                "status", status);
        if (result.success()) {
            events.info(LogEvent.MESSAGE_DELIVERED, "message delivered", fields);
        } else {
            events.warn(
                    LogEvent.MESSAGE_DELIVERY_FAILED,
                    ErrorCode.SINK_FAILURE,
                    "message delivery failed",
                    fields);
        }
    }

    private DeliveryResult deliver(Message message, String attemptId) {
        return traces.inSpan(
                "when.sink.send",
                TraceSpanKind.CLIENT,
                sinkAttributes(message),
                () -> {
                    DeliveryResult result = invokeSink(message, attemptId);
                    if (!result.success()) {
                        traces.markCurrentError(result.errorCode());
                    }
                    return result;
                });
    }

    private static TraceAttributes sinkAttributes(Message message) {
        String sinkType = message.sinkType().name().toLowerCase(java.util.Locale.ROOT);
        if (message.sinkConfig() instanceof HttpSinkConfig http) {
            String host = "invalid";
            try {
                String parsed = http.url() == null ? null : URI.create(http.url()).getHost();
                if (parsed != null && !parsed.isBlank()) {
                    host = parsed;
                }
            } catch (IllegalArgumentException ignored) {
                // The Sink returns a bounded configuration failure; tracing must remain best effort.
            }
            String method = http.method() == null || http.method().isBlank()
                    ? "POST"
                    : http.method().toUpperCase(java.util.Locale.ROOT);
            if (method.length() > 32) {
                method = "INVALID";
            }
            return TraceAttributes.of(
                    "when.sink.type", sinkType,
                    "server.address", host,
                    "http.request.method", method);
        }
        if (message.sinkConfig() instanceof KafkaSinkConfig) {
            return TraceAttributes.of(
                    "when.sink.type", sinkType,
                    "messaging.system", "kafka");
        }
        return TraceAttributes.of("when.sink.type", sinkType);
    }

    private DeliveryResult invokeSink(Message message, String attemptId) {
        final Sink sink;
        try {
            sink = sinks.require(message.sinkType());
        } catch (IllegalArgumentException exception) {
            return DeliveryResult.permanentFailure(
                    "SINK_NOT_FOUND", "no Sink plugin is registered for the message type", 0);
        }
        try {
            return sink instanceof AttemptAwareSink aware
                    ? Objects.requireNonNull(aware.deliver(message, attemptId), "Sink result")
                    : Objects.requireNonNull(sink.deliver(message), "Sink result");
        } catch (RuntimeException exception) {
            return DeliveryResult.retryableFailure(
                    "SINK_EXCEPTION", "Sink invocation failed", 0);
        }
    }

    private Duration effectiveLeaseDuration(Message message) {
        if (message.sinkConfig() instanceof HttpSinkConfig http) {
            long timeoutMillis = http.timeoutMs() == 0 ? 5_000L : Math.max(1L, http.timeoutMs());
            Duration requestBound = Duration.ofMillis(timeoutMillis).plusSeconds(30);
            if (requestBound.compareTo(leaseDuration) > 0) {
                return requestBound;
            }
        }
        return leaseDuration;
    }

    static String safeError(DeliveryResult result) {
        String code = result.errorCode() == null ? "SINK_FAILURE" : sanitize(result.errorCode());
        String summary = sanitize(result.errorMessage());
        String combined = summary == null ? code : code + ": " + summary;
        return combined.length() <= MAX_ERROR_LENGTH
                ? combined
                : combined.substring(0, MAX_ERROR_LENGTH);
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.replace('\r', ' ').replace('\n', ' ').strip();
    }

    private static long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
