package com.when.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.when.core.DeliveryResult;
import com.when.core.HttpSinkConfig;
import com.when.core.InvalidConfigException;
import com.when.core.Message;
import com.when.core.MessageStatus;
import com.when.core.Sink;
import com.when.core.SinkConfig;
import com.when.core.SinkType;
import com.when.core.StatePatch;
import com.when.core.TimeWheel;
import com.when.core.TimeWheelRegistry;
import com.when.sink.spi.SinkRegistry;
import com.when.testsupport.InMemoryStoragePlugin;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DeliveryOrchestrationTest {
    @Test
    void onlyOneConcurrentTriggerCallsSinkAndMarksDelivered() throws Exception {
        InMemoryStoragePlugin storage = new InMemoryStoragePlugin();
        storage.create(message());
        AtomicInteger calls = new AtomicInteger();
        Sink sink = sink(ignored -> {
            calls.incrementAndGet();
            return DeliveryResult.success(1);
        });
        RecordingWheel wheel = new RecordingWheel();
        DefaultDueMessageHandler handler = handler(storage, sink, wheel, Clock.systemUTC());

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Void>> tasks = java.util.stream.IntStream.range(0, 16)
                    .mapToObj(ignored -> (Callable<Void>) () -> {
                        handler.onDue("message-1");
                        return null;
                    })
                    .toList();
            executor.invokeAll(tasks);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, calls.get());
        assertEquals(MessageStatus.DELIVERED, storage.get("message-1").orElseThrow().status());
    }

    @Test
    void retryableFailureReturnsToPendingAndSchedulesThirtySecondsLater() {
        Instant now = Instant.parse("2026-08-20T08:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryStoragePlugin storage = new InMemoryStoragePlugin();
        storage.create(message());
        RecordingWheel wheel = new RecordingWheel();
        DefaultDueMessageHandler handler = handler(
                storage,
                sink(ignored -> DeliveryResult.retryableFailure("HTTP_5XX", "temporary", 1)),
                wheel,
                clock);

        handler.onDue("message-1");

        Message pending = storage.get("message-1").orElseThrow();
        assertEquals(MessageStatus.PENDING, pending.status());
        assertEquals(1, pending.retryCount());
        assertEquals(now.plusSeconds(30).toEpochMilli(), pending.nextAttemptAt());
        assertEquals("message-1", wheel.lastAdded.get().messageId());
    }

    @Test
    void permanentFailureDoesNotRetryAndRecordsSanitizedAttempt() {
        InMemoryStoragePlugin storage = new InMemoryStoragePlugin();
        storage.create(message());
        InMemoryDeliveryStateStore state = new InMemoryDeliveryStateStore();
        RecordingWheel wheel = new RecordingWheel();
        DefaultDueMessageHandler handler = new DefaultDueMessageHandler(
                "node-1",
                storage,
                new SinkRegistry(List.of(sink(ignored ->
                        DeliveryResult.permanentFailure("HTTP_4XX", "bad\nrequest", 1)))),
                new ExponentialRetryPolicy(),
                wheel.registry(),
                state,
                Clock.systemUTC(),
                Duration.ofMinutes(1));

        handler.onDue("message-1");

        assertEquals(MessageStatus.FAILED, storage.get("message-1").orElseThrow().status());
        assertFalse(storage.get("message-1").orElseThrow().lastError().contains("\n"));
        assertEquals(1, state.recentAttempts("message-1", 20).size());
    }

    @Test
    void retryPolicyUsesDocumentedBackoffAndStopsAfterFiveRetries() {
        ExponentialRetryPolicy policy = new ExponentialRetryPolicy();
        DeliveryResult retryable = DeliveryResult.retryableFailure("TEMP", "temporary", 0);
        assertEquals(List.of(30L, 60L, 120L, 240L, 480L),
                java.util.stream.IntStream.rangeClosed(1, 5)
                        .mapToObj(number -> policy.nextDelay(number).toSeconds())
                        .toList());
        assertTrue(policy.shouldRetry(retryable, 4));
        assertFalse(policy.shouldRetry(retryable, 5));
    }

    @Test
    void continuousTemporaryFailuresStopAfterSixTotalCallsAndRemainQueryable() {
        InMemoryStoragePlugin storage = new InMemoryStoragePlugin();
        storage.create(message());
        AtomicInteger calls = new AtomicInteger();
        RecordingWheel wheel = new RecordingWheel();
        DefaultDueMessageHandler handler = handler(
                storage,
                sink(ignored -> {
                    calls.incrementAndGet();
                    return DeliveryResult.retryableFailure("HTTP_5XX", "temporary", 1);
                }),
                wheel,
                Clock.systemUTC());

        for (int attempt = 0; attempt < 6; attempt++) {
            handler.onDue("message-1");
        }

        Message failed = storage.get("message-1").orElseThrow();
        assertEquals(6, calls.get());
        assertEquals(5, failed.retryCount());
        assertEquals(MessageStatus.FAILED, failed.status());
    }

    @Test
    void expiredLeaseReturnsDeliveringMessageToPendingAndReschedules() {
        InMemoryStoragePlugin storage = new InMemoryStoragePlugin();
        storage.create(message());
        storage.transition(
                "message-1", MessageStatus.PENDING, MessageStatus.DELIVERING,
                new StatePatch(null, null, null, null));
        InMemoryDeliveryStateStore state = new InMemoryDeliveryStateStore();
        Instant expiredAt = Instant.now().minusSeconds(5);
        state.tryAcquire("message-1", "stopped-node", expiredAt).orElseThrow();
        RecordingWheel wheel = new RecordingWheel();
        Clock recoveryClock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
        ExpiredDeliveryRecovery recovery = new ExpiredDeliveryRecovery(
                storage, state, wheel.registry(), recoveryClock, 10);

        assertEquals(1, recovery.recoverOnce());
        assertEquals(MessageStatus.PENDING, storage.get("message-1").orElseThrow().status());
        assertEquals("message-1", wheel.lastAdded.get().messageId());
        assertTrue(state.currentLease("message-1").isEmpty());
    }

    private static DefaultDueMessageHandler handler(
            InMemoryStoragePlugin storage, Sink sink, RecordingWheel wheel, Clock clock) {
        return new DefaultDueMessageHandler(
                "node-1", storage, new SinkRegistry(List.of(sink)),
                new ExponentialRetryPolicy(), wheel.registry(),
                new InMemoryDeliveryStateStore(), clock, Duration.ofMinutes(1));
    }

    private static Sink sink(java.util.function.Function<Message, DeliveryResult> delivery) {
        return new Sink() {
            @Override public SinkType type() { return SinkType.HTTP; }
            @Override public void validateConfig(SinkConfig config) throws InvalidConfigException { }
            @Override public DeliveryResult deliver(Message message) { return delivery.apply(message); }
        };
    }

    private static Message message() {
        long now = System.currentTimeMillis();
        return new Message(
                "message-1", now, now, "tw-0", SinkType.HTTP,
                new HttpSinkConfig("https://example.invalid/callback", "POST", Map.of(), 1_000),
                new byte[] {1}, null, MessageStatus.PENDING, 0, now, 0, null, "trace-1");
    }

    private static final class RecordingWheel implements TimeWheel {
        private final AtomicReference<Message> lastAdded = new AtomicReference<>();

        TimeWheelRegistry registry() {
            return new TimeWheelRegistry() {
                @Override
                public TimeWheel require(String timeWheelId) {
                    if (!"tw-0".equals(timeWheelId)) {
                        throw new IllegalArgumentException("unknown time wheel");
                    }
                    return RecordingWheel.this;
                }

                @Override public Collection<TimeWheel> all() { return List.of(RecordingWheel.this); }
            };
        }

        @Override public String id() { return "tw-0"; }
        @Override public void add(Message message) { lastAdded.set(message); }
        @Override public void remove(String messageId) { }
        @Override public void start() { }
        @Override public void stop() { }
    }
}
