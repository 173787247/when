package com.when.api.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.when.api.application.CancelResult;
import com.when.api.application.DelayMessageHandler;
import com.when.api.application.MessageNotFoundException;
import com.when.api.application.MessageView;
import com.when.api.application.SubmitCommand;
import com.when.api.application.SubmitResult;
import com.when.common.proto.HttpSinkConfig;
import com.when.common.proto.MessageStatus;
import com.when.common.proto.SinkConfig;
import com.when.common.proto.SinkType;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DelayMessageGrpcServerIntegrationTest {
    @Test
    void servesHealthAndAllThreeRpcsThroughARealServerAndClient() throws Exception {
        TestHandler handler = new TestHandler();
        try (Fixture fixture = Fixture.start(handler)) {
            ManagedChannel channel = fixture.channel;
            try {
                var health = HealthGrpc.newBlockingStub(channel).check(
                        HealthCheckRequest.newBuilder()
                                .setService(DelayMessageServiceGrpc.SERVICE_NAME)
                                .build());
                assertEquals(ServingStatus.SERVING, health.getStatus());

                var client = DelayMessageServiceGrpc.newBlockingStub(channel);
                SubmitResponse submitted = client.submit(validSubmit());
                QueryResponse queried = client.query(
                        QueryRequest.newBuilder().setMessageId(submitted.getMessageId()).build());
                CancelResponse cancelled = client.cancel(
                        CancelRequest.newBuilder().setMessageId(submitted.getMessageId()).build());

                assertEquals("msg-integration", submitted.getMessageId());
                assertEquals(MessageStatus.PENDING, submitted.getStatus());
                assertEquals(MessageStatus.PENDING, queried.getStatus());
                assertEquals(MessageStatus.CANCELLED, cancelled.getStatus());
                assertEquals(1, handler.submitCalls);
                assertEquals(1, handler.queryCalls);
                assertEquals(1, handler.cancelCalls);
            } finally {
                channel.shutdownNow();
                channel.awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void propagatesExpectedGrpcStatusCodesThroughClientStub() throws Exception {
        try (Fixture fixture = Fixture.start(new TestHandler())) {
            ManagedChannel channel = fixture.channel;
            try {
                var client = DelayMessageServiceGrpc.newBlockingStub(channel);
                assertCode(Status.Code.INVALID_ARGUMENT, () ->
                        client.submit(SubmitRequest.getDefaultInstance()));
                assertCode(Status.Code.NOT_FOUND, () -> client.query(
                        QueryRequest.newBuilder().setMessageId("missing").build()));
            } finally {
                channel.shutdownNow();
                channel.awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Server server;
        private final ManagedChannel channel;

        private Fixture(Server server, ManagedChannel channel) {
            this.server = server;
            this.channel = channel;
        }

        private static Fixture start(DelayMessageHandler handler) throws Exception {
            String name = "when-api-" + UUID.randomUUID();
            HealthStatusManager health = new HealthStatusManager();
            Server server = InProcessServerBuilder.forName(name)
                    .directExecutor()
                    .addService(new DelayMessageServiceImpl(handler))
                    .addService(health.getHealthService())
                    .build()
                    .start();
            health.setStatus(DelayMessageServiceGrpc.SERVICE_NAME, ServingStatus.SERVING);
            ManagedChannel channel = InProcessChannelBuilder.forName(name)
                    .directExecutor()
                    .build();
            return new Fixture(server, channel);
        }

        @Override
        public void close() throws Exception {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static SubmitRequest validSubmit() {
        return SubmitRequest.newBuilder()
                .setDelaySeconds(1)
                .setSinkType(SinkType.HTTP)
                .setSinkConfig(SinkConfig.newBuilder().setHttp(HttpSinkConfig.newBuilder()
                        .setUrl("https://example.internal/callback")
                        .setMethod("POST")))
                .build();
    }

    private static void assertCode(Status.Code expected, Runnable call) {
        try {
            call.run();
        } catch (StatusRuntimeException exception) {
            assertEquals(expected, exception.getStatus().getCode());
            return;
        }
        throw new AssertionError("expected gRPC status " + expected);
    }

    private static final class TestHandler implements DelayMessageHandler {
        private int submitCalls;
        private int queryCalls;
        private int cancelCalls;

        @Override
        public SubmitResult submit(SubmitCommand command) {
            submitCalls++;
            return new SubmitResult(
                    "msg-integration", com.when.core.MessageStatus.PENDING, command.deliverAt());
        }

        @Override
        public MessageView query(String messageId) {
            queryCalls++;
            if (messageId.equals("missing")) {
                throw new MessageNotFoundException(messageId);
            }
            return new MessageView(
                    messageId,
                    com.when.core.MessageStatus.PENDING,
                    1,
                    2,
                    0,
                    0,
                    null,
                    com.when.core.SinkType.HTTP,
                    null);
        }

        @Override
        public CancelResult cancel(String messageId) {
            cancelCalls++;
            return new CancelResult(messageId, com.when.core.MessageStatus.CANCELLED);
        }
    }
}
