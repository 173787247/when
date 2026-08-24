package com.when.observability;

import java.util.Map;
import java.util.Objects;

/** Management listener is separate from the public business listener and loopback-bound by default. */
public record ManagementConfig(String host, int port, boolean runtimeLogLevelEnabled) {
    public static final int DEFAULT_PORT = 8081;

    public ManagementConfig {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("management host must not be blank");
        }
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("management port must be between 0 and 65535");
        }
    }

    public static ManagementConfig fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    static ManagementConfig fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String host = value(environment, "WHEN_MANAGEMENT_HOST", "127.0.0.1");
        int port = integer(value(environment, "WHEN_MANAGEMENT_PORT", Integer.toString(DEFAULT_PORT)));
        boolean logLevel = Boolean.parseBoolean(value(
                environment, "WHEN_RUNTIME_LOG_LEVEL_ENABLED", "false"));
        return new ManagementConfig(host, port, logLevel);
    }

    private static int integer(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("WHEN_MANAGEMENT_PORT must be an integer", exception);
        }
    }

    private static String value(Map<String, String> environment, String key, String fallback) {
        String value = environment.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
