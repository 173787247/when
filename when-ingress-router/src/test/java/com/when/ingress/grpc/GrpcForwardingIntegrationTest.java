package com.when.ingress.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.when.api.application.CancellationRejectedException;
import com.when.api.application.SubmitCommand;
import com.when.api.application.SubmitResult;
import com.when.api.application.DelayMessageHandler;
import com.when.api.application.CancelResult;
import com.when.api.application.MessageView;
import com.when.api.grpc.DelayMessageServiceImpl;
import com.when.core.HttpSinkConfig;
import com.when.core.Message;
import com.when.core.MessageStatus;
import com.when.core.NodeEndpoint;
import com.when.core.SinkType;
import com.when.core.TimeWheel;
import com.when.core.TimeWheelRegistry;
import com.when.ingress.application.DefaultDelayMessageHandler;
import com.when.ingress.application.DelayMessageForwarder;
import com.when.ingress.application.RoutedSubmit;
import com.when.ingress.router.ConsistentHashRouter;
import com.when.testsupport.InMemoryStoragePlugin;
import com.when.testsupport.StaticClusterView;
import com.when.observability.OpenTelemetryTraceOperations;
import com.when.observability.TraceAttributes;
import com.when.observability.TraceSpanKind;
import com.when.observability.grpc.GrpcTraceServerInterceptor;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.time.Duration;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class GrpcForwardingIntegrationTest {
    @Test
    void preservesIngressIdentityWhileExecutingOnTheRemoteMaster() throws Exception {
        String wheelId = "tw-remote";
        InMemoryStoragePlugin storage = new InMemoryStoragePlugin();
        RecordingWheel wheel = new RecordingWheel(wheelId);
        var router = new ConsistentHashRouter(List.of(wheelId));
        var remote = new DefaultDelayMessageHandler(
                "node-b",
                () -> "must-not-be-used",
                router,
                new StaticClusterView(Map.of(
                        wheelId, new NodeEndpoint("node-b", "127.0.0.1", 1))),
                registry(wheel),
                storage,
                DelayMessageForwarder.localOnly());

        String serverName = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(ServerInterceptors.intercept(
                        new DelayMessageServiceImpl(remote), new ForwardingServerInterceptor()))
                .build()
                .start();
        try (GrpcDelayMessageForwarder forwarder = new GrpcDelayMessageForwarder(
                Duration.ofSeconds(5),
                ignored -> InProcessChannelBuilder.forName(serverName).directExecutor().build())) {
            var ingress = new DefaultDelayMessageHandler(
                    "node-a",
                    () -> "424242",
                    router,
                    new StaticClusterView(Map.of(
                            wheelId,
                            new NodeEndpoint("node-b", "in-process", 1))),
                    registry(),
                    storage,
                    forwarder);
            var result = ingress.submit(new SubmitCommand(
                    System.currentTimeMillis() + 10_000,
                    SinkType.HTTP,
                    new HttpSinkConfig("https://example.invalid/callback", "POST", Map.of(), 1_000),
                    new byte[] {7},
                    "grpc-forward"));

            assertEquals("424242", result.messageId());
            assertEquals(MessageStatus.PENDING, storage.get("424242").orElseThrow().status());
            assertEquals("424242", wheel.added.messageId());
            assertEquals(MessageStatus.CANCELLED, ingress.cancel("424242").status());
            assertThrows(CancellationRejectedException.class, () -> ingress.cancel("424242"));
        } finally {
            server.shutdownNow();
        }
    }

    @Test
    void propagatesOneTraceAcrossClientAndServerForwardSpans() throws Exception {
        CollectingExporter exporter = new CollectingExporter();
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                    .setTracerProvider(provider)
                    .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                    .build();
            OpenTelemetryTraceOperations traces = new OpenTelemetryTraceOperations(sdk, "when-test");
            DelayMessageHandler remote = IngressGrpcServer.transportHandler(new SuccessfulHandler(), traces);
            String serverName = InProcessServerBuilder.generateName();
            var server = InProcessServerBuilder.forName(serverName)
                    .directExecutor()
                    .addService(ServerInterceptors.intercept(
                            new DelayMessageServiceImpl(remote), new GrpcTraceServerInterceptor()))
                    .build()
                    .start();
            try (GrpcDelayMessageForwarder forwarder = new GrpcDelayMessageForwarder(
                    Duration.ofSeconds(5),
                    ignored -> InProcessChannelBuilder.forName(serverName).directExecutor().build(),
                    traces)) {
                NodeEndpoint endpoint = new NodeEndpoint("node-b", "in-process", 1);
                RoutedSubmit routed = new RoutedSubmit(
                        "message-1",
                        "legacy-trace-id",
                        "tw-1",
                        new SubmitCommand(
                                System.currentTimeMillis() + 10_000,
                                SinkType.HTTP,
                                new HttpSinkConfig("https://example.invalid/callback", "POST", Map.of(), 1_000),
                                new byte[0],
                                null));

                traces.inSpan(
                        "when.submit",
                        TraceSpanKind.SERVER,
                        TraceAttributes.of("when.sink.type", "http"),
                        () -> forwarder.submit(endpoint, routed));

                SpanData root = exporter.spans.stream()
                        .filter(span -> span.getName().equals("when.submit"))
                        .findFirst()
                        .orElseThrow();
                SpanData client = exporter.forward(SpanKind.CLIENT);
                SpanData serverSpan = exporter.forward(SpanKind.SERVER);
                assertEquals(root.getTraceId(), client.getTraceId());
                assertEquals(root.getTraceId(), serverSpan.getTraceId());
                assertEquals(root.getSpanId(), client.getParentSpanId());
                assertEquals(client.getSpanId(), serverSpan.getParentSpanId());
            } finally {
                server.shutdownNow();
            }
        }
    }

    private static TimeWheelRegistry registry(TimeWheel... wheels) {
        Map<String, TimeWheel> byId = java.util.Arrays.stream(wheels)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(TimeWheel::id, wheel -> wheel));
        return new TimeWheelRegistry() {
            @Override
            public TimeWheel require(String timeWheelId) {
                TimeWheel wheel = byId.get(timeWheelId);
                if (wheel == null) {
                    throw new IllegalArgumentException("time wheel is not hosted locally: " + timeWheelId);
                }
                return wheel;
            }

            @Override
            public Collection<TimeWheel> all() {
                return byId.values();
            }
        };
    }

    private static final class RecordingWheel implements TimeWheel {
        private final String id;
        private volatile Message added;

        private RecordingWheel(String id) {
            this.id = id;
        }

        @Override public String id() { return id; }
        @Override public void add(Message message) { added = message; }
        @Override public void remove(String messageId) { }
        @Override public void start() { }
        @Override public void stop() { }
    }

    private static final class SuccessfulHandler implements DelayMessageHandler {
        @Override
        public SubmitResult submit(SubmitCommand command) {
            return new SubmitResult("message-1", MessageStatus.PENDING, command.deliverAt());
        }

        @Override
        public MessageView query(String messageId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CancelResult cancel(String messageId) {
            throw new UnsupportedOperationException();
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

        private SpanData forward(SpanKind kind) {
            return spans.stream()
                    .filter(span -> span.getName().equals("when.forward") && span.getKind() == kind)
                    .findFirst()
                    .orElseThrow();
        }
    }
}
