package com.when.ingress.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.when.api.application.CancellationRejectedException;
import com.when.api.application.SubmitCommand;
import com.when.core.ClusterView;
import com.when.core.HttpSinkConfig;
import com.when.core.Message;
import com.when.core.MessageStatus;
import com.when.core.NodeEndpoint;
import com.when.core.StatePatch;
import com.when.core.StoragePlugin;
import com.when.core.TimeWheel;
import com.when.core.TimeWheelRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DefaultDelayMessageHandlerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-18T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void persistsBeforeAddingAndDoesNotAddWhenPersistenceFails() {
        RecordingWheel wheel = new RecordingWheel("tw-0");
        StoragePlugin failing = new MapStorage() {
            @Override
            public void create(Message message) {
                throw new IllegalStateException("storage unavailable");
            }
        };
        var handler = handler(wheel, failing);

        assertThrows(IllegalStateException.class, () -> handler.submit(command()));
        assertFalse(wheel.added);
    }

    @Test
    void validatesTimePayloadAndStrongSinkConfiguration() {
        var handler = handler(new RecordingWheel("tw-0"), new MapStorage());
        assertThrows(IngressValidationException.class, () -> handler.submit(new SubmitCommand(
                CLOCK.millis(),
                com.when.core.SinkType.HTTP,
                new HttpSinkConfig("https://example.invalid", "POST", Map.of(), 1_000),
                new byte[0],
                null)));
        assertThrows(IngressValidationException.class, () -> handler.submit(new SubmitCommand(
                CLOCK.millis() + 1_000,
                com.when.core.SinkType.HTTP,
                new HttpSinkConfig("https://example.invalid", "POST", Map.of(), 1_000),
                new byte[65_537],
                null)));
        assertThrows(IngressValidationException.class, () -> handler.submit(new SubmitCommand(
                CLOCK.millis() + 1_000,
                com.when.core.SinkType.KAFKA,
                new HttpSinkConfig("https://example.invalid", "POST", Map.of(), 1_000),
                new byte[0],
                null)));
    }

    @Test
    void failsExplicitlyWhenTheMasterIsMissing() {
        RecordingWheel wheel = new RecordingWheel("tw-0");
        TimeWheelRegistry registry = registry(wheel);
        var handler = new DefaultDelayMessageHandler(
                "node-a",
                () -> "1001",
                ignored -> "tw-0",
                ignored -> Optional.empty(),
                registry,
                new MapStorage(),
                DelayMessageForwarder.localOnly(),
                CLOCK,
                () -> "trace-1");
        assertThrows(TimeWheelMasterUnavailableException.class, () -> handler.submit(command()));
    }

    @Test
    void submitQueryAndConcurrentCancelUseThePersistentStateMachine() throws Exception {
        RecordingWheel wheel = new RecordingWheel("tw-0");
        MapStorage storage = new MapStorage();
        var handler = handler(wheel, storage);

        String id = handler.submit(command()).messageId();
        assertTrue(wheel.added);
        assertEquals(MessageStatus.PENDING, handler.query(id).status());

        AtomicInteger successes = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(10);
        try {
            for (int index = 0; index < 100; index++) {
                executor.submit(() -> {
                    try {
                        handler.cancel(id);
                        successes.incrementAndGet();
                    } catch (CancellationRejectedException expected) {
                        // Exactly one compare-and-set is allowed to win.
                    }
                });
            }
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, successes.get());
        assertEquals(MessageStatus.CANCELLED, handler.query(id).status());
        assertTrue(wheel.removed);
    }

    private static DefaultDelayMessageHandler handler(TimeWheel wheel, StoragePlugin storage) {
        TimeWheelRegistry registry = registry(wheel);
        ClusterView view = id -> Optional.of(new NodeEndpoint("node-a", "127.0.0.1", 9000));
        return new DefaultDelayMessageHandler(
                "node-a",
                () -> "1001",
                ignored -> "tw-0",
                view,
                registry,
                storage,
                DelayMessageForwarder.localOnly(),
                CLOCK,
                () -> "trace-1");
    }

    private static TimeWheelRegistry registry(TimeWheel wheel) {
        return new TimeWheelRegistry() {
            @Override
            public TimeWheel require(String timeWheelId) {
                if (!wheel.id().equals(timeWheelId)) {
                    throw new IllegalArgumentException("missing wheel");
                }
                return wheel;
            }

            @Override
            public Collection<TimeWheel> all() {
                return List.of(wheel);
            }
        };
    }

    private static SubmitCommand command() {
        return new SubmitCommand(
                CLOCK.millis() + 10_000,
                com.when.core.SinkType.HTTP,
                new HttpSinkConfig("https://example.invalid/callback", "POST", Map.of(), 1_000),
                new byte[] {1},
                "tag");
    }

    private static final class RecordingWheel implements TimeWheel {
        private final String id;
        private volatile boolean added;
        private volatile boolean removed;

        private RecordingWheel(String id) {
            this.id = id;
        }

        @Override public String id() { return id; }
        @Override public void add(Message message) { added = true; }
        @Override public void remove(String messageId) { removed = true; }
        @Override public void start() { }
        @Override public void stop() { }
    }

    private static class MapStorage implements StoragePlugin {
        private final ConcurrentHashMap<String, Message> values = new ConcurrentHashMap<>();

        @Override public String type() { return "map"; }
        @Override public void create(Message message) { values.put(message.messageId(), message); }
        @Override public Optional<Message> get(String id) { return Optional.ofNullable(values.get(id)); }

        @Override
        public boolean transition(String id, MessageStatus expected, MessageStatus target, StatePatch patch) {
            var changed = new java.util.concurrent.atomic.AtomicBoolean();
            values.computeIfPresent(id, (ignored, current) -> {
                if (current.status() != expected) return current;
                changed.set(true);
                return new Message(
                        current.messageId(), current.createdAt(), current.deliverAt(), current.timeWheelId(),
                        current.sinkType(), current.sinkConfig(), current.payload(), current.businessTag(), target,
                        current.retryCount(), current.nextAttemptAt(), current.deliveredAt(), current.lastError(),
                        current.traceId());
            });
            return changed.get();
        }

        @Override public List<Message> loadPendingByTimeWheel(String id) { return List.of(); }
        @Override public void deleteExpired(String id) { }
    }
}
