package com.when.ingress.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.when.api.application.CancellationRejectedException;
import com.when.api.application.SubmitCommand;
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
import com.when.ingress.router.ConsistentHashRouter;
import com.when.testsupport.InMemoryStoragePlugin;
import com.when.testsupport.StaticClusterView;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.time.Duration;
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
}
