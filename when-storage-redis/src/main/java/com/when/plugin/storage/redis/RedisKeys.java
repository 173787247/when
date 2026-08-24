package com.when.plugin.storage.redis;

import java.util.Objects;

/** Central definition of the small-key Redis namespace used by When messages. */
public final class RedisKeys {
    public static final String MESSAGE_PREFIX = "when:msg:";
    public static final String TIME_WHEEL_PREFIX = "when:tw:";
    public static final String INDEX_SEGMENT = ":idx:";
    public static final String DELIVERY_LEASE_PREFIX = "when:delivery:lease:";
    public static final String DELIVERY_ATTEMPT_PREFIX = "when:delivery:attempt:";
    public static final String DELIVERY_RECENT_PREFIX = "when:delivery:recent:";
    public static final String TRACE_CONTEXT_PREFIX = "when:trace:";
    public static final String ADMIN_INDEX_PREFIX = "when:admin:idx:";

    private RedisKeys() {
    }

    public static String message(String messageId) {
        return MESSAGE_PREFIX + component(messageId, "messageId");
    }

    public static String traceContext(String messageId) {
        return TRACE_CONTEXT_PREFIX + component(messageId, "messageId");
    }

    public static String scheduleIndex(String timeWheelId, String messageId) {
        return scheduleIndexPrefix(timeWheelId) + component(messageId, "messageId");
    }

    public static String scheduleIndexPrefix(String timeWheelId) {
        return TIME_WHEEL_PREFIX + component(timeWheelId, "timeWheelId") + INDEX_SEGMENT;
    }

    public static String deliveryLease(String messageId) {
        return DELIVERY_LEASE_PREFIX + component(messageId, "messageId");
    }

    public static String deliveryAttempt(String messageId, String attemptId) {
        return DELIVERY_ATTEMPT_PREFIX
                + component(messageId, "messageId")
                + ":"
                + component(attemptId, "attemptId");
    }

    public static String deliveryRecent(String messageId) {
        return DELIVERY_RECENT_PREFIX + component(messageId, "messageId");
    }

    public static String adminIndex(String hour, int shard, int part) {
        if (shard < 0 || shard >= 16 || part < 0) {
            throw new IllegalArgumentException("admin index shard or part is invalid");
        }
        return ADMIN_INDEX_PREFIX + component(hour, "hour") + ":" + shard + ":" + part;
    }

    public static String adminIndexParts(String hour, int shard) {
        if (shard < 0 || shard >= 16) {
            throw new IllegalArgumentException("admin index shard is invalid");
        }
        return ADMIN_INDEX_PREFIX + component(hour, "hour") + ":" + shard + ":parts";
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
