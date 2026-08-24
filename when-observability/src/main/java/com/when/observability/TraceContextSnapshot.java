package com.when.observability;

/** Serializable W3C context captured at a short-lived trace boundary. */
public record TraceContextSnapshot(
        String traceparent,
        String tracestate,
        String traceId,
        String spanId,
        boolean sampled) {

    public boolean valid() {
        return traceparent != null && !traceparent.isBlank();
    }

    public static TraceContextSnapshot invalid() {
        return new TraceContextSnapshot(null, null, null, null, false);
    }
}
