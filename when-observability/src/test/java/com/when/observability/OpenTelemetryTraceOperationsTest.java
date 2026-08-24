package com.when.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class OpenTelemetryTraceOperationsTest {
    @Test
    void createsParentChildTopologyAndDetachedDeliveryLink() {
        CollectingExporter exporter = new CollectingExporter();
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                    .setTracerProvider(provider)
                    .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                    .build();
            OpenTelemetryTraceOperations traces = new OpenTelemetryTraceOperations(sdk, "when-test");

            TraceContextSnapshot submit = traces.inSpan(
                    "when.submit",
                    TraceAttributes.EMPTY,
                    () -> {
                        traces.inSpan("when.storage", TraceAttributes.EMPTY, () -> null);
                        return traces.currentContext();
                    });
            TraceLink link = traces.parseLink(submit.traceparent(), submit.tracestate());
            TraceContextSnapshot delivery = traces.inLinkedSpan(
                    "when.deliver", TraceAttributes.EMPTY, link, traces::currentContext);

            SpanData submitSpan = exporter.named("when.submit");
            SpanData storageSpan = exporter.named("when.storage");
            SpanData deliverySpan = exporter.named("when.deliver");
            assertEquals(submitSpan.getSpanId(), storageSpan.getParentSpanId());
            assertNotEquals(submit.traceId(), delivery.traceId());
            assertEquals(submit.traceId(), deliverySpan.getLinks().get(0).getSpanContext().getTraceId());
        }
    }

    @Test
    void recordsSpanKindAndErrorStatusAndDoesNotLeakMdcIntoRootTasks() {
        CollectingExporter exporter = new CollectingExporter();
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                    .setTracerProvider(provider)
                    .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                    .build();
            OpenTelemetryTraceOperations traces = new OpenTelemetryTraceOperations(sdk, "when-test");

            traces.inSpan("when.sink.send", TraceSpanKind.CLIENT, TraceAttributes.EMPTY, () -> {
                traces.markCurrentError("sink failed");
                return null;
            });

            SpanData sink = exporter.named("when.sink.send");
            assertEquals(SpanKind.CLIENT, sink.getKind());
            assertEquals(StatusCode.ERROR, sink.getStatus().getStatusCode());

            MDC.put("trace_id", "stale-trace");
            MDC.put("span_id", "stale-span");
            Runnable task = traces.wrap(() -> {
                assertNull(MDC.get("trace_id"));
                assertNull(MDC.get("span_id"));
            });
            task.run();
            assertNull(MDC.get("trace_id"));
            assertNull(MDC.get("span_id"));
            MDC.clear();
        }
    }

    @Test
    void propagatesW3cContextAndRejectsMalformedParent() {
        CollectingExporter exporter = new CollectingExporter();
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                    .setTracerProvider(provider)
                    .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                    .build();
            OpenTelemetryTraceOperations traces = new OpenTelemetryTraceOperations(sdk, "when-test");
            Map<String, String> carrier = traces.inSpan(
                    "when.forward", TraceAttributes.EMPTY, traces::injectCurrentContext);

            assertTrue(carrier.containsKey("traceparent"));
            TraceContextSnapshot child = traces.withRemoteContext(
                    carrier,
                    () -> traces.inSpan("when.storage", TraceAttributes.EMPTY, traces::currentContext));
            assertEquals(carrier.get("traceparent").substring(3, 35), child.traceId());
            assertFalse(traces.parseLink("not-a-traceparent", null).valid());
        }
    }

    private static final class CollectingExporter implements SpanExporter {
        private final List<SpanData> spans = new ArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            spans.addAll(batch);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }

        SpanData named(String name) {
            return spans.stream().filter(span -> name.equals(span.getName())).findFirst().orElseThrow();
        }
    }
}
