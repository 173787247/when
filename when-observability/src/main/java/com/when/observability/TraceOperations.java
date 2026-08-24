package com.when.observability;

import java.util.Map;
import java.util.function.Supplier;

/** Trace facade: modules do not reach into the OpenTelemetry global singleton. */
public interface TraceOperations {
    <T> T inSpan(String spanName, TraceAttributes attributes, Supplier<T> action);

    default <T> T inSpan(
            String spanName,
            TraceSpanKind kind,
            TraceAttributes attributes,
            Supplier<T> action) {
        return inSpan(spanName, attributes, action);
    }

    <T> T inLinkedSpan(String spanName, TraceAttributes attributes, TraceLink link, Supplier<T> action);

    default <T> T inLinkedSpan(
            String spanName,
            TraceSpanKind kind,
            TraceAttributes attributes,
            TraceLink link,
            Supplier<T> action) {
        return inLinkedSpan(spanName, attributes, link, action);
    }

    /** Marks the active span as failed without putting exception text into span attributes. */
    default void markCurrentError(String boundedDescription) {
    }

    TraceLink parseLink(String traceparent, String tracestate);

    TraceContextSnapshot currentContext();

    Map<String, String> injectCurrentContext();

    <T> T withRemoteContext(Map<String, String> carrier, Supplier<T> action);

    Runnable wrap(Runnable task);

    static TraceOperations noop() {
        return NoopTraceOperations.INSTANCE;
    }
}
