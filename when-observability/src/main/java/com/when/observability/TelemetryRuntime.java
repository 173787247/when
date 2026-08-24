package com.when.observability;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.common.CompletableResultCode;
import java.util.Collection;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Owns the bounded, asynchronous OTLP pipeline and its TraceOperations facade. */
public final class TelemetryRuntime implements AutoCloseable {
    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");
    private static final AttributeKey<String> NODE_ID = AttributeKey.stringKey("when.node.id");

    private final OpenTelemetrySdk sdk;
    private final OpenTelemetryTraceOperations traces;

    private TelemetryRuntime(OpenTelemetrySdk sdk, OpenTelemetryTraceOperations traces) {
        this.sdk = sdk;
        this.traces = traces;
    }

    public static TelemetryRuntime fromEnvironment(String nodeId) {
        return fromEnvironment(nodeId, System.getenv(), Metrics.noop());
    }

    public static TelemetryRuntime fromEnvironment(String nodeId, Metrics metrics) {
        return fromEnvironment(nodeId, System.getenv(), metrics);
    }

    static TelemetryRuntime fromEnvironment(String nodeId, Map<String, String> environment) {
        return fromEnvironment(nodeId, environment, Metrics.noop());
    }

    static TelemetryRuntime fromEnvironment(
            String nodeId, Map<String, String> environment, Metrics metrics) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(metrics, "metrics");
        String serviceName = value(environment, "OTEL_SERVICE_NAME", "when");
        String endpoint = value(environment, "OTEL_EXPORTER_OTLP_ENDPOINT", "");
        String sampler = value(environment, "OTEL_TRACES_SAMPLER", "parentbased_traceidratio");
        if (!"parentbased_traceidratio".equalsIgnoreCase(sampler)) {
            throw new IllegalArgumentException("OTEL_TRACES_SAMPLER must be parentbased_traceidratio");
        }
        double ratio = ratio(value(environment, "OTEL_TRACES_SAMPLER_ARG", "0.1"));
        Resource resource = Resource.getDefault().merge(Resource.create(Attributes.of(
                SERVICE_NAME, serviceName,
                NODE_ID, requireText(nodeId, "nodeId"))));
        SdkTracerProviderBuilder provider = SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(ratio)));
        if (!endpoint.isBlank()) {
            SpanExporter exporter = new CountingSpanExporter(OtlpGrpcSpanExporter.builder()
                    .setEndpoint(endpoint)
                    .setTimeout(Duration.ofSeconds(5))
                    .build(), metrics);
            provider.addSpanProcessor(BatchSpanProcessor.builder(exporter)
                    .setMaxQueueSize(2_048)
                    .setMaxExportBatchSize(512)
                    .setScheduleDelay(Duration.ofSeconds(1))
                    .setExporterTimeout(Duration.ofSeconds(5))
                    .build());
        }
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(provider.build())
                .setPropagators(ContextPropagators.create(TextMapPropagator.composite(
                        W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance())))
                .build();
        return new TelemetryRuntime(sdk, new OpenTelemetryTraceOperations(sdk, serviceName));
    }

    public OpenTelemetryTraceOperations traces() {
        return traces;
    }

    public OpenTelemetrySdk sdk() {
        return sdk;
    }

    @Override
    public void close() {
        sdk.getSdkTracerProvider().close();
    }

    private static double ratio(String raw) {
        try {
            double value = Double.parseDouble(raw);
            if (value < 0 || value > 1 || !Double.isFinite(value)) {
                throw new IllegalArgumentException("OTEL_TRACES_SAMPLER_ARG must be between 0 and 1");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("OTEL_TRACES_SAMPLER_ARG must be numeric", exception);
        }
    }

    private static String value(Map<String, String> environment, String key, String fallback) {
        String configured = environment.get(key);
        return configured == null || configured.isBlank() ? fallback : configured;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static final class CountingSpanExporter implements SpanExporter {
        private final SpanExporter delegate;
        private final Metrics metrics;

        private CountingSpanExporter(SpanExporter delegate, Metrics metrics) {
            this.delegate = delegate;
            this.metrics = metrics;
        }

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            CompletableResultCode result = delegate.export(spans);
            result.whenComplete(() -> {
                if (!result.isSuccess()) {
                    metrics.incr(WhenMetrics.TRACE_EXPORT_FAILURES, "reason", "otlp_export");
                }
            });
            return result;
        }

        @Override
        public CompletableResultCode flush() {
            return delegate.flush();
        }

        @Override
        public CompletableResultCode shutdown() {
            return delegate.shutdown();
        }
    }
}
