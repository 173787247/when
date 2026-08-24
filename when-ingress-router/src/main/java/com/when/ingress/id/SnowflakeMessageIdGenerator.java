package com.when.ingress.id;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Thread-safe 41-bit timestamp, 10-bit worker and 12-bit sequence Snowflake generator. */
public final class SnowflakeMessageIdGenerator implements MessageIdGenerator {
    public static final String WORKER_ID_ENV = "WHEN_WORKER_ID";
    public static final int MAX_WORKER_ID = 1023;
    public static final long DEFAULT_EPOCH_MILLIS =
            Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();

    private static final int SEQUENCE_BITS = 12;
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;

    private final int workerId;
    private final Clock clock;
    private final long epochMillis;
    private long lastTimestamp = -1;
    private long sequence;

    public SnowflakeMessageIdGenerator(int workerId) {
        this(workerId, Clock.systemUTC(), DEFAULT_EPOCH_MILLIS);
    }

    public SnowflakeMessageIdGenerator(int workerId, Clock clock, long epochMillis) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId must be between 0 and 1023");
        }
        this.workerId = workerId;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.epochMillis = epochMillis;
    }

    public static SnowflakeMessageIdGenerator fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    public static SnowflakeMessageIdGenerator fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String value = environment.get(WORKER_ID_ENV);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(WORKER_ID_ENV + " is required");
        }
        try {
            return new SnowflakeMessageIdGenerator(Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(WORKER_ID_ENV + " must be an integer", exception);
        }
    }

    @Override
    public synchronized String nextId() {
        long wallClock = clock.millis();
        long timestamp = Math.max(wallClock, lastTimestamp);
        if (timestamp < epochMillis) {
            throw new IllegalStateException("clock is before the configured Snowflake epoch");
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // Logical-millisecond compensation avoids blocking the caller when a test clock is
                // fixed or the host clock briefly moves backwards.
                timestamp = Math.addExact(lastTimestamp, 1L);
            }
        } else {
            sequence = 0;
        }
        lastTimestamp = timestamp;
        long relativeTimestamp = timestamp - epochMillis;
        if (relativeTimestamp >= (1L << 41)) {
            throw new IllegalStateException("Snowflake timestamp capacity is exhausted");
        }
        long id = (relativeTimestamp << 22) | ((long) workerId << SEQUENCE_BITS) | sequence;
        return Long.toUnsignedString(id);
    }

}
