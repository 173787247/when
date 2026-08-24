package com.when.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.Set;
import org.slf4j.MDC;

/** Micrometer-Tracing/OpenTelemetry implementation with W3C propagation and safe scope cleanup. */
public final class OpenTelemetryTraceOperations implements TraceOperations {
    private static final Set<String> SPAN_NAMES = Set.of(
            "when.submit",
            "when.query",
            "when.cancel",
            "when.route",
            "when.forward",
            "when.storage",
            "when.metadata",
            "when.schedule",
            "when.deliver",
            "when.sink.send",
            "when.failover",
            "when.rebalance");
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier.get(key);
        }
    };
    private static final TextMapSetter<Map<String, String>> SETTER = Map::put;

    private final OpenTelemetry openTelemetry;
    private final io.opentelemetry.api.trace.Tracer tracer;
    private final OtelCurrentTraceContext currentTraceContext = new OtelCurrentTraceContext();

    public OpenTelemetryTraceOperations(OpenTelemetry openTelemetry, String serviceName) {
        this.openTelemetry = Objects.requireNonNull(openTelemetry, "openTelemetry");
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName must not be blank");
        }
        this.tracer = openTelemetry.getTracer(serviceName);
    }

    @Override
    public <T> T inSpan(String spanName, TraceAttributes attributes, Supplier<T> action) {
        return run(spanName, TraceSpanKind.INTERNAL, attributes, null, false, action);
    }

    @Override
    public <T> T inSpan(
            String spanName,
            TraceSpanKind kind,
            TraceAttributes attributes,
            Supplier<T> action) {
        return run(spanName, kind, attributes, null, false, action);
    }

    @Override
    public <T> T inLinkedSpan(
            String spanName, TraceAttributes attributes, TraceLink link, Supplier<T> action) {
        return run(spanName, TraceSpanKind.INTERNAL, attributes, link, true, action);
    }

    @Override
    public <T> T inLinkedSpan(
            String spanName,
            TraceSpanKind kind,
            TraceAttributes attributes,
            TraceLink link,
            Supplier<T> action) {
        return run(spanName, kind, attributes, link, true, action);
    }

    @Override
    public void markCurrentError(String boundedDescription) {
        Span span = Span.current();
        if (!span.getSpanContext().isValid()) {
            return;
        }
        String description = boundedDescription == null || boundedDescription.isBlank()
                ? "operation failed"
                : boundedDescription.substring(0, Math.min(128, boundedDescription.length()));
        span.setStatus(StatusCode.ERROR, description);
    }

    @Override
    public TraceLink parseLink(String traceparent, String tracestate) {
        if (traceparent == null || traceparent.isBlank()) {
            return TraceLink.invalid();
        }
        Map<String, String> carrier = new HashMap<>();
        carrier.put("traceparent", traceparent);
        if (tracestate != null && !tracestate.isBlank()) {
            carrier.put("tracestate", tracestate);
        }
        Context extracted = openTelemetry.getPropagators().getTextMapPropagator()
                .extract(Context.root(), carrier, GETTER);
        SpanContext spanContext = Span.fromContext(extracted).getSpanContext();
        return spanContext.isValid()
                ? new TraceLink(traceparent, tracestate, extracted, true)
                : TraceLink.invalid();
    }

    @Override
    public TraceContextSnapshot currentContext() {
        SpanContext context = Span.current().getSpanContext();
        if (!context.isValid()) {
            return TraceContextSnapshot.invalid();
        }
        String flags = context.isSampled() ? "01" : "00";
        String traceparent = "00-" + context.getTraceId() + "-" + context.getSpanId() + "-" + flags;
        String tracestate = context.getTraceState().isEmpty() ? null : context.getTraceState().asMap().entrySet()
                .stream().map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(","));
        return new TraceContextSnapshot(
                traceparent, tracestate, context.getTraceId(), context.getSpanId(), context.isSampled());
    }

    @Override
    public Map<String, String> injectCurrentContext() {
        Map<String, String> carrier = new HashMap<>();
        openTelemetry.getPropagators().getTextMapPropagator()
                .inject(Context.current(), carrier, SETTER);
        return Map.copyOf(carrier);
    }

    @Override
    public <T> T withRemoteContext(Map<String, String> carrier, Supplier<T> action) {
        Objects.requireNonNull(carrier, "carrier");
        Objects.requireNonNull(action, "action");
        Context extracted = openTelemetry.getPropagators().getTextMapPropagator()
                .extract(Context.root(), carrier, GETTER);
        try (Scope ignored = extracted.makeCurrent()) {
            return action.get();
        }
    }

    @Override
    public Runnable wrap(Runnable task) {
        Objects.requireNonNull(task, "task");
        Runnable withMdc = () -> {
            try (MdcScope mdc = MdcScope.open(Span.current())) {
                task.run();
            }
        };
        return currentTraceContext.wrap(withMdc);
    }

    private <T> T run(
            String spanName,
            TraceSpanKind kind,
            TraceAttributes attributes,
            TraceLink link,
            boolean detached,
            Supplier<T> action) {
        requireSpanName(spanName);
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(attributes, "attributes");
        Objects.requireNonNull(action, "action");
        SpanBuilder builder = tracer.spanBuilder(spanName).setSpanKind(toOpenTelemetry(kind));
        if (detached) {
            builder.setNoParent();
        }
        if (link != null && link.valid()) {
            builder.addLink(Span.fromContext(link.context()).getSpanContext());
        }
        attributes.values().forEach((key, value) -> builder.setAttribute(AttributeKey.stringKey(key), value));
        Span span = builder.startSpan();
        try (Scope ignored = span.makeCurrent(); MdcScope mdc = MdcScope.open(span)) {
            return action.get();
        } catch (RuntimeException | Error failure) {
            span.recordException(failure);
            span.setStatus(StatusCode.ERROR, failure.getClass().getSimpleName());
            throw failure;
        } finally {
            span.end();
        }
    }

    private static SpanKind toOpenTelemetry(TraceSpanKind kind) {
        return switch (kind) {
            case INTERNAL -> SpanKind.INTERNAL;
            case SERVER -> SpanKind.SERVER;
            case CLIENT -> SpanKind.CLIENT;
        };
    }

    private static void requireSpanName(String value) {
        if (value == null || !SPAN_NAMES.contains(value)) {
            throw new IllegalArgumentException("span name must be a bounded registered When operation");
        }
    }

    private static final class MdcScope implements AutoCloseable {
        private final String previousTrace;
        private final String previousSpan;

        private MdcScope(String previousTrace, String previousSpan) {
            this.previousTrace = previousTrace;
            this.previousSpan = previousSpan;
        }

        static MdcScope open(Span span) {
            String oldTrace = MDC.get("trace_id");
            String oldSpan = MDC.get("span_id");
            SpanContext context = span.getSpanContext();
            if (context.isValid()) {
                MDC.put("trace_id", context.getTraceId());
                MDC.put("span_id", context.getSpanId());
            } else {
                MDC.remove("trace_id");
                MDC.remove("span_id");
            }
            return new MdcScope(oldTrace, oldSpan);
        }

        @Override
        public void close() {
            restore("trace_id", previousTrace);
            restore("span_id", previousSpan);
        }

        private static void restore(String key, String value) {
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        }
    }
}
