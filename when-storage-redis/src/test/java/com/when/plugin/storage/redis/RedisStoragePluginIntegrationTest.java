package com.when.plugin.storage.redis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.when.core.HttpSinkConfig;
import com.when.core.Message;
import com.when.core.MessageStatus;
import com.when.core.StatePatch;
import com.when.core.StoragePlugin;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RedisStoragePluginIntegrationTest {
    private static String host;
    private static int port;
    private static JedisPool inspectionPool;

    @BeforeAll
    static void connectToHarnessRedis() {
        String configuredPort = System.getenv("WHEN_REDIS_PORT");
        Assumptions.assumeTrue(configuredPort != null && !configuredPort.isBlank());
        host = System.getenv().getOrDefault("WHEN_REDIS_HOST", "127.0.0.1");
        port = Integer.parseInt(configuredPort);
        inspectionPool = new JedisPool(host, port);
        try (Jedis jedis = inspectionPool.getResource()) {
            assertEquals("PONG", jedis.ping());
            jedis.flushDB();
        }
    }

    @AfterEach
    void clearRedis() {
        if (inspectionPool != null) {
            try (Jedis jedis = inspectionPool.getResource()) {
                jedis.flushDB();
            }
        }
    }

    @AfterAll
    static void closeInspectionPool() {
        if (inspectionPool != null) {
            inspectionPool.close();
        }
    }

    @Test
    void createAndGetRoundTripAllFieldsAndSetTtlOnBothSmallKeys() {
        long now = System.currentTimeMillis();
        Message original = message("round-trip", "wheel-a", now + 60_000);
        try (RedisStoragePlugin storage = storage(Duration.ofDays(7), Clock.systemUTC())) {
            storage.create(original);
            Message loaded = storage.get(original.messageId()).orElseThrow();

            assertMessageEquals(original, loaded);
            try (Jedis jedis = inspectionPool.getResource()) {
                assertEquals(2L, jedis.dbSize());
                assertEquals("string", jedis.type(RedisKeys.message(original.messageId())));
                assertEquals("string", jedis.type(RedisKeys.scheduleIndex("wheel-a", original.messageId())));
                assertTrue(jedis.pttl(RedisKeys.message(original.messageId())) > Duration.ofDays(6).toMillis());
                assertTrue(jedis.pttl(RedisKeys.scheduleIndex("wheel-a", original.messageId()))
                        > Duration.ofDays(6).toMillis());
            }
        }
    }

    @Test
    void onlyOneOfOneHundredConcurrentClaimTransitionsSucceeds() throws Exception {
        long now = System.currentTimeMillis();
        Message original = message("contended", "wheel-a", now + 60_000);
        try (RedisStoragePlugin storage = storage(Duration.ofDays(7), Clock.systemUTC())) {
            storage.create(original);
            ExecutorService executor = Executors.newFixedThreadPool(20);
            try {
                List<Callable<Boolean>> calls = new ArrayList<>();
                for (int i = 0; i < 100; i++) {
                    calls.add(() -> storage.transition(
                            original.messageId(),
                            MessageStatus.PENDING,
                            MessageStatus.DELIVERING,
                            new StatePatch(null, null, null, null)));
                }
                List<Future<Boolean>> results = executor.invokeAll(calls);
                long successes = 0;
                for (Future<Boolean> result : results) {
                    if (result.get()) {
                        successes++;
                    }
                }
                assertEquals(1L, successes);
            } finally {
                executor.shutdownNow();
            }
            assertEquals(MessageStatus.DELIVERING, storage.get(original.messageId()).orElseThrow().status());
            try (Jedis jedis = inspectionPool.getResource()) {
                assertFalse(jedis.exists(RedisKeys.scheduleIndex("wheel-a", original.messageId())));
            }
        }
    }

    @Test
    void loadPendingReturnsOnlyPendingMessagesForRequestedWheel() {
        long now = System.currentTimeMillis();
        try (RedisStoragePlugin storage = storage(Duration.ofDays(7), Clock.systemUTC())) {
            Message first = message("first", "wheel-a", now + 30_000);
            Message second = message("second", "wheel-a", now + 20_000);
            Message otherWheel = message("other", "wheel-b", now + 10_000);
            storage.create(first);
            storage.create(second);
            storage.create(otherWheel);
            assertTrue(storage.transition(
                    first.messageId(),
                    MessageStatus.PENDING,
                    MessageStatus.DELIVERING,
                    new StatePatch(null, null, null, null)));

            List<Message> pending = storage.loadPendingByTimeWheel("wheel-a");
            assertEquals(List.of("second"), pending.stream().map(Message::messageId).toList());
        }
    }

    @Test
    void retryTransitionRecreatesIndexAndExtendsTtl() {
        long now = System.currentTimeMillis();
        try (RedisStoragePlugin storage = storage(Duration.ofDays(7), Clock.systemUTC())) {
            Message original = message("retry", "wheel-a", now + 10_000);
            storage.create(original);
            assertTrue(storage.transition(
                    original.messageId(),
                    MessageStatus.PENDING,
                    MessageStatus.DELIVERING,
                    new StatePatch(null, null, null, null)));
            long retryAt = now + Duration.ofDays(10).toMillis();
            assertTrue(storage.transition(
                    original.messageId(),
                    MessageStatus.DELIVERING,
                    MessageStatus.PENDING,
                    new StatePatch(1, retryAt, null, "temporary failure")));

            Message retried = storage.get(original.messageId()).orElseThrow();
            assertEquals(1, retried.retryCount());
            assertEquals(retryAt, retried.nextAttemptAt());
            try (Jedis jedis = inspectionPool.getResource()) {
                assertEquals(Long.toString(retryAt),
                        jedis.get(RedisKeys.scheduleIndex("wheel-a", original.messageId())));
                assertTrue(jedis.pttl(RedisKeys.message(original.messageId()))
                        > Duration.ofDays(16).toMillis());
            }
        }
    }

    @Test
    void terminalMessageRemainsUntilRetentionAndThenCanBeDeleted() {
        long now = System.currentTimeMillis();
        MutableClock clock = new MutableClock(Instant.ofEpochMilli(now));
        Duration retention = Duration.ofHours(1);
        try (RedisStoragePlugin storage = storage(retention, clock)) {
            Message original = message("terminal", "wheel-a", now);
            storage.create(original);
            assertTrue(storage.transition(
                    original.messageId(),
                    MessageStatus.PENDING,
                    MessageStatus.DELIVERING,
                    new StatePatch(null, null, null, null)));
            assertTrue(storage.transition(
                    original.messageId(),
                    MessageStatus.DELIVERING,
                    MessageStatus.DELIVERED,
                    new StatePatch(null, null, now, null)));

            storage.deleteExpired(original.messageId());
            assertTrue(storage.get(original.messageId()).isPresent());
            clock.advance(retention.plusMillis(1));
            storage.deleteExpired(original.messageId());
            assertTrue(storage.get(original.messageId()).isEmpty());
            try (Jedis jedis = inspectionPool.getResource()) {
                assertEquals(0L, jedis.dbSize());
            }
        }
    }

    @Test
    void oneMessageCreatesTwoIndependentKeysRatherThanAnAggregate() {
        long now = System.currentTimeMillis();
        try (RedisStoragePlugin storage = storage(Duration.ofDays(7), Clock.systemUTC())) {
            for (int i = 0; i < 25; i++) {
                storage.create(message("small-" + i, "wheel-a", now + 60_000 + i));
            }
            try (Jedis jedis = inspectionPool.getResource()) {
                assertEquals(50L, jedis.dbSize());
                assertEquals(25L, countMatching(jedis, RedisKeys.MESSAGE_PREFIX + "*"));
            }
        }
    }

    @Test
    void unavailableRedisMakesCreateFail() throws Exception {
        int unavailablePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unavailablePort = socket.getLocalPort();
        }
        RedisStorageConfig config = new RedisStorageConfig(
                "127.0.0.1", unavailablePort, null, Duration.ofDays(7), 100, 32);
        try (RedisStoragePlugin storage = new RedisStoragePlugin(config)) {
            assertThrows(RedisStorageException.class,
                    () -> storage.create(message("unavailable", "wheel-a", System.currentTimeMillis() + 60_000)));
        }
    }

    @Test
    void serviceProviderIsRegistered() {
        assertTrue(ServiceLoader.load(StoragePlugin.class).stream()
                .map(ServiceLoader.Provider::type)
                .anyMatch(RedisStoragePlugin.class::equals));
    }

    private static RedisStoragePlugin storage(Duration retention, Clock clock) {
        return new RedisStoragePlugin(
                new RedisStorageConfig(host, port, null, retention, 2_000, 32), clock);
    }

    private static Message message(String id, String wheel, long dueAt) {
        return new Message(
                id,
                dueAt - 10_000,
                dueAt,
                wheel,
                com.when.core.SinkType.HTTP,
                new HttpSinkConfig(
                        "https://example.invalid/callback", "POST", Map.of("X-Test", "value"), 5_000),
                new byte[] {1, 2, 3, 4},
                "orders",
                MessageStatus.PENDING,
                0,
                dueAt,
                0,
                null,
                "trace-" + id);
    }

    private static long countMatching(Jedis jedis, String pattern) {
        long count = 0;
        String cursor = "0";
        var params = new redis.clients.jedis.params.ScanParams().match(pattern).count(100);
        do {
            var result = jedis.scan(cursor, params);
            count += result.getResult().size();
            cursor = result.getCursor();
        } while (!"0".equals(cursor));
        return count;
    }

    private static void assertMessageEquals(Message expected, Message actual) {
        assertEquals(expected.messageId(), actual.messageId());
        assertEquals(expected.createdAt(), actual.createdAt());
        assertEquals(expected.deliverAt(), actual.deliverAt());
        assertEquals(expected.timeWheelId(), actual.timeWheelId());
        assertEquals(expected.sinkType(), actual.sinkType());
        assertEquals(expected.sinkConfig(), actual.sinkConfig());
        assertArrayEquals(expected.payload(), actual.payload());
        assertEquals(expected.businessTag(), actual.businessTag());
        assertEquals(expected.status(), actual.status());
        assertEquals(expected.retryCount(), actual.retryCount());
        assertEquals(expected.nextAttemptAt(), actual.nextAttemptAt());
        assertEquals(expected.deliveredAt(), actual.deliveredAt());
        assertEquals(expected.lastError(), actual.lastError());
        assertEquals(expected.traceId(), actual.traceId());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
