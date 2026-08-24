package com.when.plugin.storage.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.when.core.DeliveryResult;
import com.when.core.HttpSinkConfig;
import com.when.core.Message;
import com.when.core.MessageStatus;
import com.when.core.SinkType;
import com.when.delivery.DeliveryAttempt;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

class RedisDeliveryStateStoreIntegrationTest {
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
    static void closePool() {
        if (inspectionPool != null) {
            inspectionPool.close();
        }
    }

    @Test
    void leaseIsExclusiveDiscoverableAndReleasedByMatchingAttempt() {
        RedisStorageConfig config = config();
        try (RedisStoragePlugin messages = new RedisStoragePlugin(config);
                RedisDeliveryStateStore deliveries = new RedisDeliveryStateStore(config)) {
            messages.create(message());
            Instant now = Instant.now();
            var lease = deliveries.tryAcquire(
                    "delivery-message", "node-a", now.plusSeconds(30)).orElseThrow();

            assertTrue(deliveries.tryAcquire(
                    "delivery-message", "node-b", now.plusSeconds(30)).isEmpty());
            assertEquals(lease, deliveries.currentLease("delivery-message").orElseThrow());
            deliveries.complete(
                    "delivery-message", "different-attempt", DeliveryResult.success(1));
            assertTrue(deliveries.currentLease("delivery-message").isPresent());
            deliveries.complete("delivery-message", lease.attemptId(), DeliveryResult.success(1));
            assertTrue(deliveries.currentLease("delivery-message").isEmpty());
        }
    }

    @Test
    void recentAttemptListKeepsOnlyLatestTwentyWithMessageTtl() {
        RedisStorageConfig config = config();
        try (RedisStoragePlugin messages = new RedisStoragePlugin(config);
                RedisDeliveryStateStore deliveries = new RedisDeliveryStateStore(config)) {
            messages.create(message());
            Instant base = Instant.now();
            for (int index = 0; index < 25; index++) {
                deliveries.appendAttempt(new DeliveryAttempt(
                        "delivery-message",
                        "attempt-" + index,
                        base.plusSeconds(index),
                        base.plusSeconds(index + 1L),
                        false,
                        true,
                        "HTTP_5XX",
                        10));
            }

            var recent = deliveries.recentAttempts("delivery-message", 20);
            assertEquals(20, recent.size());
            assertEquals("attempt-24", recent.get(0).attemptId());
            assertEquals("attempt-5", recent.get(19).attemptId());
            try (Jedis jedis = inspectionPool.getResource()) {
                long messageTtl = jedis.pttl(RedisKeys.message("delivery-message"));
                long recentTtl = jedis.pttl(RedisKeys.deliveryRecent("delivery-message"));
                assertTrue(recentTtl > 0);
                assertTrue(Math.abs(messageTtl - recentTtl) < 2_000);
                String encoded = jedis.get(RedisKeys.deliveryAttempt("delivery-message", "attempt-24"));
                assertFalse(encoded.contains("payload"));
                assertFalse(encoded.contains("response"));
            }
        }
    }

    private static RedisStorageConfig config() {
        return new RedisStorageConfig(host, port, null, Duration.ofDays(7), 2_000, 32);
    }

    private static Message message() {
        long now = System.currentTimeMillis();
        return new Message(
                "delivery-message", now, now + 60_000, "tw-0", SinkType.HTTP,
                new HttpSinkConfig("https://example.invalid/callback", "POST", Map.of(), 1_000),
                new byte[] {1, 2, 3}, null, MessageStatus.PENDING,
                0, now + 60_000, 0, null, "trace-1");
    }
}
