package com.when.observability;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Deterministic test/local store; production wiring uses the Redis implementation. */
public final class InMemoryTraceContextStore implements TraceContextStore {
    private final ConcurrentHashMap<String, TraceContextSnapshot> contexts = new ConcurrentHashMap<>();

    @Override
    public void put(String messageId, TraceContextSnapshot context) {
        contexts.put(requireId(messageId), java.util.Objects.requireNonNull(context, "context"));
    }

    @Override
    public Optional<TraceContextSnapshot> get(String messageId) {
        return Optional.ofNullable(contexts.get(requireId(messageId)));
    }

    @Override
    public void delete(String messageId) {
        contexts.remove(requireId(messageId));
    }

    private static String requireId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        return value;
    }
}
