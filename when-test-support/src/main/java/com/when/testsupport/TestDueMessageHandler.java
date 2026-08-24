package com.when.testsupport;

import com.when.core.DeliveryResult;
import com.when.core.DueMessageHandler;
import com.when.core.Message;
import com.when.core.MessageStatus;
import com.when.core.Sink;
import com.when.core.StatePatch;
import com.when.core.StoragePlugin;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/** First-run due pipeline: atomically claims, records via a test Sink, then writes a terminal state. */
public final class TestDueMessageHandler implements DueMessageHandler {
    private final StoragePlugin storage;
    private final Map<com.when.core.SinkType, Sink> sinks;
    private final Clock clock;

    public TestDueMessageHandler(StoragePlugin storage, Map<com.when.core.SinkType, Sink> sinks) {
        this(storage, sinks, Clock.systemUTC());
    }

    public TestDueMessageHandler(
            StoragePlugin storage,
            Map<com.when.core.SinkType, Sink> sinks,
            Clock clock) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.sinks = Map.copyOf(Objects.requireNonNull(sinks, "sinks"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void onDue(String messageId) {
        Message pending = storage.get(messageId).orElse(null);
        if (pending == null || !storage.transition(
                messageId,
                MessageStatus.PENDING,
                MessageStatus.DELIVERING,
                new StatePatch(null, null, null, null))) {
            return;
        }
        Sink sink = sinks.get(pending.sinkType());
        DeliveryResult result = sink == null
                ? new DeliveryResult(false, false, "test Sink is unavailable", 0)
                : sink.deliver(pending);
        if (result.success()) {
            storage.transition(
                    messageId,
                    MessageStatus.DELIVERING,
                    MessageStatus.DELIVERED,
                    new StatePatch(null, null, clock.millis(), null));
        } else {
            storage.transition(
                    messageId,
                    MessageStatus.DELIVERING,
                    MessageStatus.FAILED,
                    new StatePatch(null, null, null, sanitize(result.errorMessage())));
        }
    }

    private static String sanitize(String error) {
        if (error == null || error.isBlank()) {
            return "test delivery failed";
        }
        return error.length() <= 256 ? error : error.substring(0, 256);
    }
}
