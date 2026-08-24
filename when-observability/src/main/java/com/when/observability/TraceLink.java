package com.when.observability;

import io.opentelemetry.context.Context;

/** Parsed link target. The opaque Context is never serialized or logged. */
public record TraceLink(String traceparent, String tracestate, Context context, boolean valid) {
    public static TraceLink invalid() {
        return new TraceLink(null, null, Context.root(), false);
    }
}
