package com.when.plugin.storage.redis;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Connection and retention settings for the Redis storage plugin. */
public record RedisStorageConfig(
        String host,
        int port,
        String password,
        Duration terminalRetention,
        int connectionTimeoutMillis,
        int scanBatchSize) {

    public static final Duration DEFAULT_TERMINAL_RETENTION = Duration.ofDays(7);

    public RedisStorageConfig {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Redis host must not be blank");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Redis port must be between 1 and 65535");
        }
        Objects.requireNonNull(terminalRetention, "terminalRetention");
        if (terminalRetention.isNegative()) {
            throw new IllegalArgumentException("Terminal retention must not be negative");
        }
        if (connectionTimeoutMillis < 1 || scanBatchSize < 1) {
            throw new IllegalArgumentException("Redis timeout and scan batch size must be positive");
        }
        password = password == null || password.isBlank() ? null : password;
    }

    public static RedisStorageConfig fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    static RedisStorageConfig fromEnvironment(Map<String, String> environment) {
        String host = environment.getOrDefault("WHEN_REDIS_HOST", "127.0.0.1");
        int port = parseInt(environment, "WHEN_REDIS_PORT", 6379);
        String password = environment.get("WHEN_REDIS_PASSWORD");
        long retentionDays = parseLong(environment, "WHEN_REDIS_RETENTION_DAYS", 7L);
        int timeoutMillis = parseInt(environment, "WHEN_REDIS_TIMEOUT_MS", 2_000);
        int scanBatchSize = parseInt(environment, "WHEN_REDIS_SCAN_BATCH_SIZE", 256);
        return new RedisStorageConfig(
                host,
                port,
                password,
                Duration.ofDays(retentionDays),
                timeoutMillis,
                scanBatchSize);
    }

    private static int parseInt(Map<String, String> environment, String name, int defaultValue) {
        String value = environment.get(name);
        try {
            return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    private static long parseLong(Map<String, String> environment, String name, long defaultValue) {
        String value = environment.get(name);
        try {
            return value == null || value.isBlank() ? defaultValue : Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }
}
