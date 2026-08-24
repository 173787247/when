package com.when.cluster.controller;

import java.time.Duration;

public record RebalancePolicy(
        Duration nodeStablePeriod,
        Duration timeWheelCooldown,
        int maxParallelMoves) {
    public static final Duration DEFAULT_NODE_STABLE_PERIOD = Duration.ofSeconds(30);
    public static final Duration DEFAULT_TIME_WHEEL_COOLDOWN = Duration.ofSeconds(60);
    public static final int DEFAULT_MAX_PARALLEL_MOVES = 2;

    public RebalancePolicy {
        nodeStablePeriod = positiveOrZero(nodeStablePeriod, "nodeStablePeriod");
        timeWheelCooldown = positiveOrZero(timeWheelCooldown, "timeWheelCooldown");
        if (maxParallelMoves <= 0) {
            throw new IllegalArgumentException("maxParallelMoves must be positive");
        }
    }

    public static RebalancePolicy defaults() {
        return new RebalancePolicy(
                DEFAULT_NODE_STABLE_PERIOD,
                DEFAULT_TIME_WHEEL_COOLDOWN,
                DEFAULT_MAX_PARALLEL_MOVES);
    }

    private static Duration positiveOrZero(Duration value, String field) {
        if (value == null) {
            throw new NullPointerException(field);
        }
        if (value.isNegative()) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value;
    }
}
