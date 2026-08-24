package com.when.observability;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;

class MicrometerMetricsTest {
    @Test
    void exposesRegisteredMetricsWithFixedLowCardinalityTags() {
        try (MicrometerMetrics metrics = new MicrometerMetrics()) {
            metrics.incr(WhenMetrics.MESSAGES_SUBMITTED, "sink_type", "http");
            metrics.observe(WhenMetrics.DELIVERY_LAG, 0.5, "sink_type", "http");
            AtomicInteger pending = new AtomicInteger(2);
            metrics.gauge(WhenMetrics.MESSAGES_IN_STATE, pending::get, "state", "pending");

            String scrape = metrics.scrape();
            assertTrue(scrape.contains("when_messages_submitted_total"));
            assertTrue(scrape.contains("sink_type=\"http\""));
            assertTrue(scrape.contains("when_delivery_lag_seconds_bucket"));
            assertTrue(scrape.contains("le=\"0.5\""));
            assertTrue(scrape.contains("when_messages_in_state"));
        }
    }

    @Test
    void rejectsHighCardinalityOrUnregisteredLabels() {
        try (MicrometerMetrics metrics = new MicrometerMetrics()) {
            assertThrows(IllegalArgumentException.class, () -> metrics.incr(
                    WhenMetrics.MESSAGES_SUBMITTED,
                    "message_id", "message-1"));
            assertThrows(IllegalArgumentException.class, () -> metrics.incr(
                    "when_arbitrary_total",
                    "result", "ok"));
        }
    }

    @Test
    void sampledHistogramObservationCarriesAnOpenMetricsExemplar() {
        try (SdkTracerProvider provider = SdkTracerProvider.builder().build();
                MicrometerMetrics metrics = new MicrometerMetrics()) {
            OpenTelemetryTraceOperations traces = new OpenTelemetryTraceOperations(
                    OpenTelemetrySdk.builder().setTracerProvider(provider).build(), "when-test");

            traces.inSpan("when.deliver", TraceAttributes.EMPTY, () -> {
                metrics.observe(WhenMetrics.DELIVERY_LAG, 0.5, "sink_type", "http");
                return null;
            });

            String scrape = metrics.scrape();
            assertTrue(scrape.contains("# {"), scrape);
            assertTrue(scrape.contains("trace_id=\""), scrape);
            assertTrue(scrape.contains("span_id=\""), scrape);
        }
    }
}
