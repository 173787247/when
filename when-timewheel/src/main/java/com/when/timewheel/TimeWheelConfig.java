package com.when.timewheel;

/** Resource and precision limits for one logical time wheel. */
public record TimeWheelConfig(
        long tickMillis,
        int wheelSize,
        long maxPendingTimeouts,
        int dueThreads,
        int dueQueueCapacity) {

    public static final long DEFAULT_TICK_MILLIS = 100L;
    public static final int DEFAULT_WHEEL_SIZE = 512;
    public static final long DEFAULT_MAX_PENDING_TIMEOUTS = 100_000L;
    public static final int DEFAULT_DUE_THREADS = 1;
    public static final int DEFAULT_DUE_QUEUE_CAPACITY = 1_024;

    public TimeWheelConfig {
        if (tickMillis <= 0) {
            throw new IllegalArgumentException("tickMillis must be positive");
        }
        if (wheelSize < 2 || (wheelSize & (wheelSize - 1)) != 0) {
            throw new IllegalArgumentException("wheelSize must be a power of two and at least 2");
        }
        if (maxPendingTimeouts <= 0) {
            throw new IllegalArgumentException("maxPendingTimeouts must be positive");
        }
        if (dueThreads <= 0) {
            throw new IllegalArgumentException("dueThreads must be positive");
        }
        if (dueQueueCapacity <= 0) {
            throw new IllegalArgumentException("dueQueueCapacity must be positive");
        }
    }

    public static TimeWheelConfig defaults() {
        return new TimeWheelConfig(
                DEFAULT_TICK_MILLIS,
                DEFAULT_WHEEL_SIZE,
                DEFAULT_MAX_PENDING_TIMEOUTS,
                DEFAULT_DUE_THREADS,
                DEFAULT_DUE_QUEUE_CAPACITY);
    }

    /** Reads documented environment overrides while retaining bounded defaults. */
    public static TimeWheelConfig fromEnvironment() {
        return new TimeWheelConfig(
                positiveLong("WHEN_TIMEWHEEL_TICK_MS", DEFAULT_TICK_MILLIS),
                positiveInt("WHEN_TIMEWHEEL_SIZE", DEFAULT_WHEEL_SIZE),
                positiveLong("WHEN_TIMEWHEEL_MAX_PENDING", DEFAULT_MAX_PENDING_TIMEOUTS),
                positiveInt("WHEN_TIMEWHEEL_DUE_THREADS", DEFAULT_DUE_THREADS),
                positiveInt("WHEN_TIMEWHEEL_DUE_QUEUE_CAPACITY", DEFAULT_DUE_QUEUE_CAPACITY));
    }

    private static long positiveLong(String name, long defaultValue) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a positive integer", exception);
        }
    }

    private static int positiveInt(String name, int defaultValue) {
        long value = positiveLong(name, defaultValue);
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " is too large");
        }
        return (int) value;
    }
}
