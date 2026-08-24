package com.when.observability;

import java.util.Optional;

/** Persistence boundary for linking a later Delivery trace to its completed Submit trace. */
public interface TraceContextStore {
    void put(String messageId, TraceContextSnapshot context);

    Optional<TraceContextSnapshot> get(String messageId);

    void delete(String messageId);

    static TraceContextStore noop() {
        return NoopTraceContextStore.INSTANCE;
    }
}
