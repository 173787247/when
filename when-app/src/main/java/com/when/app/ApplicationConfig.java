package com.when.app;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;

/** Non-secret node settings consumed by the application composition root. */
public record ApplicationConfig(
        String nodeId,
        int workerId,
        int grpcPort,
        List<String> timeWheelIds) {

    public ApplicationConfig {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        if (workerId < 0 || workerId > 1023) {
            throw new IllegalArgumentException("workerId must be between 0 and 1023");
        }
        if (grpcPort < 0 || grpcPort > 65_535) {
            throw new IllegalArgumentException("grpcPort must be between 0 and 65535");
        }
        timeWheelIds = List.copyOf(Objects.requireNonNull(timeWheelIds, "timeWheelIds"));
        if (timeWheelIds.isEmpty() || timeWheelIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("at least one non-blank timeWheelId is required");
        }
    }

    public static ApplicationConfig fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    static ApplicationConfig fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String nodeId = required(environment, "WHEN_NODE_ID");
        int workerId = integer(environment, "WHEN_WORKER_ID", -1);
        int grpcPort = integer(environment, "WHEN_GRPC_PORT", -1);
        int count = integer(environment, "WHEN_TIMEWHEEL_COUNT", 1);
        if (count < 1) {
            throw new IllegalStateException("WHEN_TIMEWHEEL_COUNT must be positive");
        }
        List<String> ids = IntStream.range(0, count)
                .mapToObj(index -> "tw-" + index)
                .toList();
        return new ApplicationConfig(nodeId, workerId, grpcPort, ids);
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private static int integer(Map<String, String> environment, String name, int defaultValue) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            if (defaultValue >= 0) {
                return defaultValue;
            }
            throw new IllegalStateException(name + " is required");
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(name + " must be an integer", exception);
        }
    }
}
