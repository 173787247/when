package com.when.e2e;

import com.google.protobuf.ByteString;
import com.when.api.grpc.CancelRequest;
import com.when.api.grpc.DelayMessageServiceGrpc;
import com.when.api.grpc.QueryRequest;
import com.when.api.grpc.SubmitRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** External TCP gRPC client for the single-node integration flow. */
public final class SingleNodeE2eClient {
    private SingleNodeE2eClient() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("command is required: health, submit, query, or cancel");
        }
        String host = environment("WHEN_E2E_SERVER_HOST", "127.0.0.1");
        int port = integerEnvironment("WHEN_E2E_SERVER_PORT");
        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        try {
            switch (args[0]) {
                case "health" -> health(channel);
                case "submit" -> submit(channel, delaySeconds(args));
                case "query" -> query(channel, requiredArgument(args, 1, "message_id"));
                case "cancel" -> cancel(channel, requiredArgument(args, 1, "message_id"));
                default -> throw new IllegalArgumentException("unsupported command: " + args[0]);
            }
        } finally {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static void health(ManagedChannel channel) {
        HealthCheckResponse response = HealthGrpc.newBlockingStub(channel).check(
                HealthCheckRequest.newBuilder()
                        .setService(DelayMessageServiceGrpc.SERVICE_NAME)
                        .build());
        if (response.getStatus() != HealthCheckResponse.ServingStatus.SERVING) {
            throw new IllegalStateException("health status is " + response.getStatus());
        }
        System.out.println("health=SERVING");
    }

    private static void submit(ManagedChannel channel, int delaySeconds) {
        var response = DelayMessageServiceGrpc.newBlockingStub(channel).submit(SubmitRequest.newBuilder()
                .setDelaySeconds(delaySeconds)
                .setSinkType(com.when.common.proto.SinkType.HTTP)
                .setSinkConfig(com.when.common.proto.SinkConfig.newBuilder()
                        .setHttp(com.when.common.proto.HttpSinkConfig.newBuilder()
                                .setUrl("https://example.invalid/callback")
                                .setMethod("POST")
                                .setTimeoutMs(1_000)))
                .setPayload(ByteString.EMPTY)
                .setBusinessTag("e2e")
                .build());
        System.out.println("message_id=" + response.getMessageId());
        System.out.println("status=" + response.getStatus());
        System.out.println("deliver_at=" + response.getDeliverAt());
    }

    private static void query(ManagedChannel channel, String messageId) {
        var response = DelayMessageServiceGrpc.newBlockingStub(channel).query(
                QueryRequest.newBuilder().setMessageId(messageId).build());
        System.out.println("message_id=" + response.getMessageId());
        System.out.println("status=" + response.getStatus());
    }

    private static void cancel(ManagedChannel channel, String messageId) {
        var response = DelayMessageServiceGrpc.newBlockingStub(channel).cancel(
                CancelRequest.newBuilder().setMessageId(messageId).build());
        System.out.println("message_id=" + response.getMessageId());
        System.out.println("status=" + response.getStatus());
    }

    private static int delaySeconds(String[] args) {
        String value = requiredArgument(args, 1, "delay_seconds");
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > 2_592_000) {
                throw new IllegalArgumentException("delay_seconds must be between 1 and 2592000");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("delay_seconds must be an integer", exception);
        }
    }

    private static String requiredArgument(String[] args, int index, String name) {
        if (args.length <= index || args[index].isBlank()) {
            throw new IllegalArgumentException(name + " is required; arguments=" + Arrays.toString(args));
        }
        return args[index];
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int integerEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65_535) {
                throw new IllegalStateException(name + " must be between 1 and 65535");
            }
            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(name + " must be an integer", exception);
        }
    }
}
