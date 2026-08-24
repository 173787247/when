package com.when.observability;

import com.when.core.Message;
import com.when.core.MessageStatus;
import com.when.core.StatePatch;
import com.when.core.StoragePlugin;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicLong;

/** Storage decorator that observes every Redis SPI operation without changing storage semantics. */
public final class ObservedStoragePlugin implements StoragePlugin {
    private final StoragePlugin delegate;
    private final Metrics metrics;
    private final TraceOperations traces;
    private final EnumMap<MessageStatus, AtomicLong> states = new EnumMap<>(MessageStatus.class);

    public ObservedStoragePlugin(StoragePlugin delegate, Metrics metrics, TraceOperations traces) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.traces = Objects.requireNonNull(traces, "traces");
        for (MessageStatus status : MessageStatus.values()) {
            AtomicLong value = new AtomicLong();
            states.put(status, value);
            metrics.gauge(
                    WhenMetrics.MESSAGES_IN_STATE,
                    value::get,
                    "state", status.name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    @Override
    public String type() {
        return delegate.type();
    }

    @Override
    public void create(Message message) {
        observed("create", () -> {
            delegate.create(message);
            states.get(MessageStatus.PENDING).incrementAndGet();
            return null;
        });
    }

    @Override
    public Optional<Message> get(String messageId) {
        return observed("get", () -> delegate.get(messageId));
    }

    @Override
    public boolean transition(
            String messageId, MessageStatus expected, MessageStatus target, StatePatch patch) {
        return observed("transition", () -> {
            boolean changed = delegate.transition(messageId, expected, target, patch);
            if (changed) {
                states.get(expected).updateAndGet(value -> Math.max(0, value - 1));
                states.get(target).incrementAndGet();
            }
            return changed;
        });
    }

    @Override
    public List<Message> loadPendingByTimeWheel(String timeWheelId) {
        return observed("load_pending", () -> {
            List<Message> pending = delegate.loadPendingByTimeWheel(timeWheelId);
            states.get(MessageStatus.PENDING).updateAndGet(value -> Math.max(value, pending.size()));
            return pending;
        });
    }

    @Override
    public void deleteExpired(String messageId) {
        observed("delete_expired", () -> {
            delegate.deleteExpired(messageId);
            return null;
        });
    }

    private <T> T observed(String operation, Supplier<T> action) {
        long started = System.nanoTime();
        try {
            T value = traces.inSpan(
                    "when.storage",
                    TraceSpanKind.CLIENT,
                    TraceAttributes.of("db.system", "redis", "db.operation.name", operation),
                    action);
            metrics.incr(WhenMetrics.REDIS_OPERATIONS, "operation", operation, "status", "success");
            return value;
        } catch (RuntimeException failure) {
            metrics.incr(WhenMetrics.REDIS_OPERATIONS, "operation", operation, "status", "failure");
            throw failure;
        } finally {
            metrics.observe(
                    WhenMetrics.REDIS_OPERATION_DURATION,
                    Math.max(0d, (System.nanoTime() - started) / 1_000_000_000d),
                    "operation", operation);
        }
    }
}
