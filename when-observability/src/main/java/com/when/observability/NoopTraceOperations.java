package com.when.observability;

import java.util.Map;
import java.util.function.Supplier;

enum NoopTraceOperations implements TraceOperations {
    INSTANCE;

    @Override
    public <T> T inSpan(String spanName, TraceAttributes attributes, Supplier<T> action) {
        return action.get();
    }

    @Override
    public <T> T inLinkedSpan(
            String spanName, TraceAttributes attributes, TraceLink link, Supplier<T> action) {
        return action.get();
    }

    @Override
    public TraceLink parseLink(String traceparent, String tracestate) {
        return TraceLink.invalid();
    }

    @Override
    public TraceContextSnapshot currentContext() {
        return TraceContextSnapshot.invalid();
    }

    @Override
    public Map<String, String> injectCurrentContext() {
        return Map.of();
    }

    @Override
    public <T> T withRemoteContext(Map<String, String> carrier, Supplier<T> action) {
        return action.get();
    }

    @Override
    public Runnable wrap(Runnable task) {
        return task;
    }
}
