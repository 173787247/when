package com.when.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Prometheus implementation with an explicit metric/tag allowlist and fixed buckets. */
public final class MicrometerMetrics implements Metrics, AutoCloseable {
    public static final String OPEN_METRICS_CONTENT_TYPE =
            "application/openmetrics-text; version=1.0.0; charset=utf-8";
    private static final Set<String> FORBIDDEN_TAGS = Set.of(
            "message_id", "trace_id", "tw_id", "url", "topic", "exception", "error");
    private static final Map<String, Set<String>> ALLOWED_TAGS = Map.ofEntries(
            Map.entry(WhenMetrics.MESSAGES_SUBMITTED, Set.of("sink_type")),
            Map.entry(WhenMetrics.SINK_DELIVERIES, Set.of("sink_type", "result")),
            Map.entry(WhenMetrics.MESSAGES_IN_STATE, Set.of("state")),
            Map.entry(WhenMetrics.DELIVERY_LAG, Set.of("sink_type")),
            Map.entry(WhenMetrics.SINK_DELIVERY_DURATION, Set.of("sink_type")),
            Map.entry(WhenMetrics.MASTER_FAILOVER, Set.of("result", "reason")),
            Map.entry(WhenMetrics.CONTROLLER_ELECTIONS, Set.of("result")),
            Map.entry(WhenMetrics.REPLICA_SYNC_QUEUE_SIZE, Set.of("node_id")),
            Map.entry(WhenMetrics.REPLICA_REBUILD, Set.of("result")),
            Map.entry(WhenMetrics.REDIS_OPERATIONS, Set.of("operation", "status")),
            Map.entry(WhenMetrics.ETCD_OPERATIONS, Set.of("operation", "status")),
            Map.entry(WhenMetrics.REDIS_OPERATION_DURATION, Set.of("operation")),
            Map.entry(WhenMetrics.ETCD_OPERATION_DURATION, Set.of("operation")),
            Map.entry(WhenMetrics.TRACE_EXPORT_FAILURES, Set.of("reason")),
            Map.entry(WhenMetrics.DUE_HANDOFF_REJECTED, Set.of("reason")));
    private static final double[] DELIVERY_BUCKETS = {0.1, 0.5, 1, 2, 5, 10, 30, 60};

    private final PrometheusMeterRegistry registry;
    private final Set<MeterKey> gauges = ConcurrentHashMap.newKeySet();
    private final List<Supplier<Number>> gaugeReferences = new ArrayList<>();

    public MicrometerMetrics() {
        this(new PrometheusMeterRegistry(
                PrometheusConfig.DEFAULT,
                new PrometheusRegistry(),
                Clock.SYSTEM,
                OpenTelemetrySpanContext.INSTANCE));
    }

    public MicrometerMetrics(PrometheusMeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void incr(String name, String... tags) {
        Tags safeTags = validate(name, tags);
        Counter.builder(name).tags(safeTags).register(registry).increment();
    }

    @Override
    public void observe(String name, double value, String... tags) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException("metric observation must be finite and non-negative");
        }
        Tags safeTags = validate(name, tags);
        DistributionSummary.Builder builder = DistributionSummary.builder(name)
                .baseUnit("seconds")
                .tags(safeTags);
        if (WhenMetrics.DELIVERY_LAG.equals(name)
                || WhenMetrics.SINK_DELIVERY_DURATION.equals(name)) {
            builder.serviceLevelObjectives(DELIVERY_BUCKETS);
        }
        builder.register(registry).record(value);
    }

    @Override
    public synchronized void gauge(String name, Supplier<Number> value, String... tags) {
        Objects.requireNonNull(value, "value");
        Tags safeTags = validate(name, tags);
        MeterKey key = new MeterKey(name, safeTags.stream().toList());
        if (gauges.add(key)) {
            gaugeReferences.add(value);
            Gauge.builder(name, value, supplier -> {
                        Number number = supplier.get();
                        return number == null ? Double.NaN : number.doubleValue();
                    })
                    .tags(safeTags)
                    .register(registry);
        }
    }

    @Override
    public String scrape() {
        return registry.scrape(OPEN_METRICS_CONTENT_TYPE);
    }

    public PrometheusMeterRegistry registry() {
        return registry;
    }

    @Override
    public void close() {
        registry.close();
        synchronized (this) {
            gaugeReferences.clear();
            gauges.clear();
        }
    }

    private static Tags validate(String name, String... pairs) {
        Set<String> allowed = ALLOWED_TAGS.get(name);
        if (allowed == null) {
            throw new IllegalArgumentException("unregistered metric name: " + name);
        }
        if (pairs == null || pairs.length % 2 != 0) {
            throw new IllegalArgumentException("metric tags must be key/value pairs");
        }
        List<Tag> tags = new ArrayList<>(pairs.length / 2);
        for (int index = 0; index < pairs.length; index += 2) {
            String key = requireValue(pairs[index], "tag name");
            String value = requireValue(pairs[index + 1], "tag value");
            if (FORBIDDEN_TAGS.contains(key) || !allowed.contains(key)) {
                throw new IllegalArgumentException("tag is not allowed for metric " + name + ": " + key);
            }
            if (value.length() > 128) {
                throw new IllegalArgumentException("metric tag value is too long");
            }
            tags.add(Tag.of(key, value));
        }
        Set<String> actual = tags.stream().map(Tag::getKey).collect(java.util.stream.Collectors.toSet());
        if (!actual.equals(allowed)) {
            throw new IllegalArgumentException("metric " + name + " requires tags " + allowed);
        }
        return Tags.of(tags);
    }

    private static String requireValue(String value, String description) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(description + " must not be blank");
        }
        return value;
    }

    private record MeterKey(String name, List<Tag> tags) {
    }

    private enum OpenTelemetrySpanContext
            implements io.prometheus.metrics.tracer.common.SpanContext {
        INSTANCE;

        @Override
        public String getCurrentTraceId() {
            return Span.current().getSpanContext().getTraceId();
        }

        @Override
        public String getCurrentSpanId() {
            return Span.current().getSpanContext().getSpanId();
        }

        @Override
        public boolean isCurrentSpanSampled() {
            return Span.current().getSpanContext().isSampled();
        }

        @Override
        public void markCurrentSpanAsExemplar() {
            Span.current().setAttribute(AttributeKey.stringKey(EXEMPLAR_ATTRIBUTE_NAME), EXEMPLAR_ATTRIBUTE_VALUE);
        }
    }
}
