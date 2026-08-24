package com.when.plugin.storage.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.when.core.HttpSinkConfig;
import com.when.core.Message;
import com.when.core.MessageStatus;
import com.when.core.SinkType;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

class RedisAdminQueryStoreIntegrationTest {
    private static JedisPool inspection;
    private static RedisStorageConfig config;

    @BeforeAll
    static void connect() {
        String port = System.getenv("WHEN_REDIS_PORT");
        Assumptions.assumeTrue(port != null && !port.isBlank());
        config = new RedisStorageConfig(
                System.getenv().getOrDefault("WHEN_REDIS_HOST", "127.0.0.1"),
                Integer.parseInt(port), System.getenv("WHEN_REDIS_PASSWORD"),
                Duration.ofDays(7), 2_000, 32);
        inspection = new JedisPool(config.host(), config.port());
        try (Jedis jedis = inspection.getResource()) { jedis.flushDB(); }
    }

    @AfterEach
    void clear() {
        if (inspection != null) try (Jedis jedis = inspection.getResource()) { jedis.flushDB(); }
    }

    @AfterAll
    static void close() { if (inspection != null) inspection.close(); }

    @Test
    void boundedIndexFiltersAndCursorDoesNotScanMessageKeyspace() {
        long now = System.currentTimeMillis();
        try (RedisStoragePlugin storage = new RedisStoragePlugin(config, Clock.systemUTC());
             RedisAdminQueryStore admin = new RedisAdminQueryStore(config, storage)) {
            for (int index = 0; index < 3; index++) {
                Message message = message("admin-" + index, now + index * 1_000L,
                        index == 1 ? "other" : "orders");
                storage.create(message);
                admin.index(message.messageId(), message.deliverAt());
            }

            var first = admin.query(new RedisAdminQueryStore.AdminMessageQuery(
                    MessageStatus.PENDING, SinkType.HTTP, "orders", now - 1, now + 10_000, null, 1));
            assertEquals(1, first.items().size());
            assertTrue(first.hasMore());
            assertNotNull(first.nextCursor());

            var second = admin.query(new RedisAdminQueryStore.AdminMessageQuery(
                    MessageStatus.PENDING, SinkType.HTTP, "orders", now - 1, now + 10_000,
                    first.nextCursor(), 1));
            assertEquals(1, second.items().size());
            assertNotEquals(first.items().get(0).messageId(), second.items().get(0).messageId());
            assertFalse(second.hasMore());

            assertThrows(RedisAdminQueryStore.InvalidAdminCursorException.class, () -> admin.query(
                    new RedisAdminQueryStore.AdminMessageQuery(
                            null, null, null, now - 1, now + 10_000, first.nextCursor(), 10)));
            try (Jedis jedis = inspection.getResource()) {
                assertTrue(jedis.keys(RedisKeys.ADMIN_INDEX_PREFIX + "*").stream()
                        .allMatch(key -> key.startsWith(RedisKeys.ADMIN_INDEX_PREFIX)));
            }
        }
    }

    private static Message message(String id, long deliverAt, String tag) {
        return new Message(
                id, deliverAt - 1_000, deliverAt, "tw-admin", SinkType.HTTP,
                new HttpSinkConfig("https://example.test/callback", "POST", Map.of(), 1_000),
                new byte[] {1}, tag, MessageStatus.PENDING, 0, deliverAt, 0, null, "trace-" + id);
    }
}
