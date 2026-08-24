package com.when.acceptance;


import com.google.protobuf.ByteString;
import com.when.api.grpc.DelayMessageServiceGrpc;
import com.when.api.grpc.QueryRequest;
import com.when.api.grpc.SubmitRequest;
import com.when.cluster.etcd.EtcdMetadataClient;
import com.when.cluster.membership.EtcdClusterMembership;
import com.when.cluster.membership.NodeInfo;
import com.when.core.MessageStatus;
import com.when.core.NodeEndpoint;
import com.when.core.Sink;
import com.when.core.SinkType;
import com.when.ingress.application.DefaultDelayMessageHandler;
import com.when.ingress.application.DelayMessageForwarder;
import com.when.ingress.grpc.GrpcDelayMessageForwarder;
import com.when.ingress.grpc.IngressGrpcServer;
import com.when.ingress.id.SnowflakeMessageIdGenerator;
import com.when.ingress.router.ConsistentHashRouter;
import com.when.plugin.storage.redis.RedisStorageConfig;
import com.when.plugin.storage.redis.RedisStoragePlugin;
import com.when.testsupport.RecordingTestSink;
import com.when.testsupport.StaticClusterView;
import com.when.testsupport.TestDueMessageHandler;
import com.when.timewheel.DefaultTimeWheelRegistry;
import com.when.timewheel.NettyTimeWheel;
import com.when.timewheel.TimeWheelRebuilder;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

/** Executable first-stage acceptance using the Harness-managed Redis process. */
public final class FirstStageAcceptance {
    private static final String TIME_WHEEL_ID = "tw-acceptance";
    private static final String MASTER_NODE_ID = "node-b";

    private FirstStageAcceptance() {
    }

    public static void main(String[] args) throws Exception {
        verifyThreeNodeMembershipAndSingleController();
        RedisStorageConfig redisConfig = RedisStorageConfig.fromEnvironment();
        try (RedisStoragePlugin storage = new RedisStoragePlugin(redisConfig)) {
            RecordingTestSink sink = new RecordingTestSink(SinkType.HTTP);
            TestDueMessageHandler dueHandler =
                    new TestDueMessageHandler(storage, Map.<SinkType, Sink>of(SinkType.HTTP, sink));
            NettyTimeWheel remoteWheel = new NettyTimeWheel(TIME_WHEEL_ID, dueHandler);
            DefaultTimeWheelRegistry remoteRegistry = new DefaultTimeWheelRegistry(List.of(remoteWheel));
            ConsistentHashRouter router = new ConsistentHashRouter(List.of(TIME_WHEEL_ID));
            StaticClusterView remoteView = new StaticClusterView(Map.of(
                    TIME_WHEEL_ID, new NodeEndpoint(MASTER_NODE_ID, "127.0.0.1", 1)));
            DefaultDelayMessageHandler remoteHandler = new DefaultDelayMessageHandler(
                    MASTER_NODE_ID,
                    new SnowflakeMessageIdGenerator(2),
                    router,
                    remoteView,
                    remoteRegistry,
                    storage,
                    DelayMessageForwarder.localOnly());

            remoteWheel.start();
            try {
                try (IngressGrpcServer remoteServer = new IngressGrpcServer(0, remoteHandler).start();
                    GrpcDelayMessageForwarder forwarder = new GrpcDelayMessageForwarder()) {
                StaticClusterView ingressView = new StaticClusterView(Map.of(
                        TIME_WHEEL_ID, new NodeEndpoint(MASTER_NODE_ID, "127.0.0.1", remoteServer.port())));
                DefaultDelayMessageHandler ingress = new DefaultDelayMessageHandler(
                        "node-a",
                        new SnowflakeMessageIdGenerator(1),
                        router,
                        ingressView,
                        new DefaultTimeWheelRegistry(),
                        storage,
                        forwarder);

                    try (IngressGrpcServer ingressServer = new IngressGrpcServer(0, ingress).start()) {
                    ManagedChannel channel = ManagedChannelBuilder
                            .forAddress("127.0.0.1", ingressServer.port())
                            .usePlaintext()
                            .build();
                    try {
                        DelayMessageServiceGrpc.DelayMessageServiceBlockingStub client =
                                DelayMessageServiceGrpc.newBlockingStub(channel);
                        String deliveredId = client.submit(request(System.currentTimeMillis() + 750)).getMessageId();
                        require(
                                client.query(QueryRequest.newBuilder().setMessageId(deliveredId).build()).getStatus()
                                        == com.when.common.proto.MessageStatus.PENDING,
                                "server Submit did not durably persist a PENDING message");
                        awaitStatus(client, deliveredId, MessageStatus.DELIVERED, Duration.ofSeconds(8));
                        require(
                                sink.deliveredMessageIds().contains(deliveredId),
                                "forwarded message did not reach the recording Sink");

                        String cancelledId = client.submit(request(System.currentTimeMillis() + 30_000)).getMessageId();
                        require(
                                client.cancel(com.when.api.grpc.CancelRequest.newBuilder()
                                                .setMessageId(cancelledId)
                                                .build())
                                                .getStatus()
                                        == com.when.common.proto.MessageStatus.CANCELLED,
                                "pending cancellation did not succeed");
                        require(
                                client.query(QueryRequest.newBuilder().setMessageId(cancelledId).build()).getStatus()
                                        == com.when.common.proto.MessageStatus.CANCELLED,
                                "cancelled terminal state was not retained");

                        String rebuiltId = client.submit(request(System.currentTimeMillis() + 2_000)).getMessageId();
                        remoteWheel.stop();
                        NettyTimeWheel rebuiltWheel = new NettyTimeWheel(TIME_WHEEL_ID, dueHandler);
                        try {
                            int rebuilt = new TimeWheelRebuilder(storage).rebuildAndStart(rebuiltWheel);
                            require(rebuilt == 1, "restart rebuild did not load exactly the pending message");
                            awaitStatus(client, rebuiltId, MessageStatus.DELIVERED, Duration.ofSeconds(8));
                        } finally {
                            rebuiltWheel.stop();
                        }
                    } finally {
                        channel.shutdownNow();
                    }
                }
                }
            } finally {
                remoteWheel.stop();
            }
        }
        System.out.println("first-stage acceptance completed");
    }

    private static void verifyThreeNodeMembershipAndSingleController() {
        try (EtcdMetadataClient client1 = EtcdMetadataClient.fromEnvironment();
                EtcdMetadataClient client2 = EtcdMetadataClient.fromEnvironment();
                EtcdMetadataClient client3 = EtcdMetadataClient.fromEnvironment()) {
            try (EtcdClusterMembership node1 = new EtcdClusterMembership(client1);
                    EtcdClusterMembership node2 = new EtcdClusterMembership(client2);
                    EtcdClusterMembership node3 = new EtcdClusterMembership(client3)) {
                long startedAt = System.currentTimeMillis();
                node1.registerSelf(new NodeInfo("acceptance-node-1", "127.0.0.1", 19_001, startedAt, 0), 901);
                node2.registerSelf(new NodeInfo("acceptance-node-2", "127.0.0.1", 19_002, startedAt, 0), 902);
                node3.registerSelf(new NodeInfo("acceptance-node-3", "127.0.0.1", 19_003, startedAt, 0), 903);
                require(node1.listNodes().size() == 3, "three nodes did not register in ETCD");
                long winners = java.util.stream.Stream.of(node1, node2, node3)
                        .filter(EtcdClusterMembership::tryBecomeController)
                        .count();
                require(winners == 1, "Controller election did not produce exactly one winner");
                require(
                        node2.currentController().isPresent()
                                && node2.currentController().equals(node3.currentController()),
                        "nodes do not observe the same Controller");
            }
        }
    }

    private static SubmitRequest request(long deliverAt) {
        return SubmitRequest.newBuilder()
                .setDeliverAt(deliverAt)
                .setSinkType(com.when.common.proto.SinkType.HTTP)
                .setSinkConfig(com.when.common.proto.SinkConfig.newBuilder()
                        .setHttp(com.when.common.proto.HttpSinkConfig.newBuilder()
                                .setUrl("https://example.invalid/callback")
                                .setMethod("POST")
                                .setTimeoutMs(1_000)))
                .setPayload(ByteString.copyFrom(new byte[] {1, 2, 3}))
                .setBusinessTag("first-stage")
                .build();
    }

    private static void awaitStatus(
            DelayMessageServiceGrpc.DelayMessageServiceBlockingStub client,
            String messageId,
            MessageStatus expected,
            Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (client.query(QueryRequest.newBuilder().setMessageId(messageId).build()).getStatus()
                    == toProto(expected)) {
                return;
            }
            Thread.sleep(25);
        }
        throw new IllegalStateException(
                "message " + messageId + " did not reach expected state " + expected);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static com.when.common.proto.MessageStatus toProto(MessageStatus status) {
        return switch (status) {
            case PENDING -> com.when.common.proto.MessageStatus.PENDING;
            case DELIVERING -> com.when.common.proto.MessageStatus.DELIVERING;
            case DELIVERED -> com.when.common.proto.MessageStatus.DELIVERED;
            case FAILED -> com.when.common.proto.MessageStatus.FAILED;
            case CANCELLED -> com.when.common.proto.MessageStatus.CANCELLED;
        };
    }
}
