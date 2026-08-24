package com.when.observability;

import java.util.Optional;

enum NoopTraceContextStore implements TraceContextStore {
    INSTANCE;

    @Override
    public void put(String messageId, TraceContextSnapshot context) {
    }

    @Override
    public Optional<TraceContextSnapshot> get(String messageId) {
        return Optional.empty();
    }

    @Override
    public void delete(String messageId) {
    }
}
