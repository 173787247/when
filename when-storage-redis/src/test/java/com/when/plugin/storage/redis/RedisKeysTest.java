package com.when.plugin.storage.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedisKeysTest {
    @Test
    void buildsTheDocumentedKeySpace() {
        assertEquals("when:msg:message-1", RedisKeys.message("message-1"));
        assertEquals("when:tw:wheel-1:idx:message-1", RedisKeys.scheduleIndex("wheel-1", "message-1"));
    }

    @Test
    void rejectsComponentsThatCouldChangePrefixScanScope() {
        assertThrows(IllegalArgumentException.class, () -> RedisKeys.message("bad:id"));
        assertThrows(IllegalArgumentException.class, () -> RedisKeys.scheduleIndexPrefix("wheel*"));
    }

    @Test
    void readsConnectionSettingsFromDocumentedEnvironmentVariables() {
        RedisStorageConfig config = RedisStorageConfig.fromEnvironment(Map.of(
                "WHEN_REDIS_HOST", "redis.internal",
                "WHEN_REDIS_PORT", "6380",
                "WHEN_REDIS_PASSWORD", "injected-value",
                "WHEN_REDIS_RETENTION_DAYS", "9"));
        assertEquals("redis.internal", config.host());
        assertEquals(6380, config.port());
        assertEquals("injected-value", config.password());
        assertEquals(Duration.ofDays(9), config.terminalRetention());
    }
}
