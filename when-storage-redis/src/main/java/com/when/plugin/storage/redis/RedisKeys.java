package com.when.plugin.storage.redis;

import java.util.Objects;

/** Central definition of the small-key Redis namespace used by When messages. */
public final class RedisKeys {
    public static final String MESSAGE_PREFIX = "when:msg:";
    public static final String TIME_WHEEL_PREFIX = "when:tw:";
    public static final String INDEX_SEGMENT = ":idx:";

    private RedisKeys() {
    }

    public static String message(String messageId) {
        return MESSAGE_PREFIX + component(messageId, "messageId");
    }

    public static String scheduleIndex(String timeWheelId, String messageId) {
        return scheduleIndexPrefix(timeWheelId) + component(messageId, "messageId");
    }

    public static String scheduleIndexPrefix(String timeWheelId) {
        return TIME_WHEEL_PREFIX + component(timeWheelId, "timeWheelId") + INDEX_SEGMENT;
    }

    private static String component(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.indexOf(':') >= 0 || containsGlob(value)) {
            throw new IllegalArgumentException(name + " contains characters that are not valid in a Redis key component");
        }
        return value;
    }

    private static boolean containsGlob(String value) {
        return value.indexOf('*') >= 0
                || value.indexOf('?') >= 0
                || value.indexOf('[') >= 0
                || value.indexOf(']') >= 0;
    }
}
