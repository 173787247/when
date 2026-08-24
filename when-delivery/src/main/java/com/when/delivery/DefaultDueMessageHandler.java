package com.when.delivery;

import com.when.core.DeliveryResult;
import com.when.core.DueMessageHandler;
import com.when.core.HttpSinkConfig;
import com.when.core.Message;
import com.when.core.MessageStatus;
import com.when.core.Sink;
import com.when.core.SinkType;
import com.when.core.StatePatch;
import com.when.core.StoragePlugin;
import com.when.core.TimeWheelRegistry;
import com.when.sink.spi.AttemptAwareSink;
import com.when.sink.spi.SinkRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
                DEFAULT_LEASE_DURATION);
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
        this.nodeId = requireText(nodeId, "nodeId");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.sinks = Objects.requireNonNull(sinks, "sinks");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.timeWheels = Objects.requireNonNull(timeWheels, "timeWheels");
        this.deliveryState = Objects.requireNonNull(deliveryState, "deliveryState");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseDuration = requirePositive(leaseDuration, "leaseDuration");
    }

    @Override
    public void onDue(String messageId) {
        Message message = storage.get(requireText(messageId, "messageId")).orElse(null);
        if (message == null || message.status() != MessageStatus.PENDING) {
            return;
        }

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
            }
            return;
        }

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

    private DeliveryResult deliver(Message message, String attemptId) {
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
