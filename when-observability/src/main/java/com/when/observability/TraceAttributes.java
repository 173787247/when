package com.when.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded low-cardinality attributes for one operation span. */
public record TraceAttributes(Map<String, String> values) {
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "when.sink.type",
            "when.route.result",
            "when.route.local",
            "when.target.node",
            "rpc.system",
            "rpc.method",
            "server.address",
            "db.system",
            "db.operation.name",
            "when.timewheel.level",
            "when.delivery.attempt",
            "http.request.method",
            "messaging.system",
            "when.failover.reason",
            "when.failover.result",
            "when.rebalance.result",
            "when.assignment.version");
    public static final TraceAttributes EMPTY = new TraceAttributes(Map.of());

    public TraceAttributes {
        Objects.requireNonNull(values, "values");
        if (values.size() > 16) {
            throw new IllegalArgumentException("a span may have at most 16 business attributes");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key == null || key.isBlank() || !ALLOWED_KEYS.contains(key)
                    || value == null || value.length() > 256) {
                throw new IllegalArgumentException("span attributes must be named and bounded");
            }
            copy.put(key, value);
        });
        values = Map.copyOf(copy);
    }

    public static TraceAttributes of(String... pairs) {
        if (pairs == null || pairs.length % 2 != 0) {
            throw new IllegalArgumentException("trace attributes must be key/value pairs");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put(pairs[index], pairs[index + 1]);
        }
        return new TraceAttributes(values);
    }
}
