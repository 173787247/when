package com.when.acceptance;


import com.when.api.application.SubmitCommand;
import com.when.api.grpc.DelayMessageServiceImpl;
import com.when.cluster.etcd.EtcdMetadataClient;
import com.when.cluster.membership.EtcdClusterMembership;
import com.when.cluster.membership.NodeInfo;
import com.when.core.HttpSinkConfig;
import com.when.core.MessageStatus;
import com.when.core.NodeEndpoint;
import com.when.core.Sink;
import com.when.core.SinkType;
import com.when.ingress.application.DefaultDelayMessageHandler;
import com.when.ingress.application.DelayMessageForwarder;
import com.when.ingress.grpc.GrpcDelayMessageForwarder;
import com.when.ingress.grpc.ForwardingServerInterceptor;
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
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;

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
            String serverName = InProcessServerBuilder.generateName();
            var server = InProcessServerBuilder.forName(serverName)
                    .directExecutor()
                    .addService(ServerInterceptors.intercept(
                            new DelayMessageServiceImpl(remoteHandler),
                            new ForwardingServerInterceptor()))
                    .build()
                    .start();
            try (GrpcDelayMessageForwarder forwarder = new GrpcDelayMessageForwarder(
                    Duration.ofSeconds(5),
                    ignored -> InProcessChannelBuilder.forName(serverName).directExecutor().build())) {
                StaticClusterView ingressView = new StaticClusterView(Map.of(
                        TIME_WHEEL_ID,
                        new NodeEndpoint(MASTER_NODE_ID, "in-process", 1)));
                DefaultDelayMessageHandler ingress = new DefaultDelayMessageHandler(
                        "node-a",
                        new SnowflakeMessageIdGenerator(1),
                        router,
                        ingressView,
                        new DefaultTimeWheelRegistry(),
                        storage,
                        forwarder);

                String deliveredId = ingress.submit(command(System.currentTimeMillis() + 750)).messageId();
                require(
                        ingress.query(deliveredId).status() == MessageStatus.PENDING,
                        "submitted message was not durably PENDING");
                awaitStatus(ingress, deliveredId, MessageStatus.DELIVERED, Duration.ofSeconds(8));
                require(
                        sink.deliveredMessageIds().contains(deliveredId),
                        "forwarded message did not reach the recording Sink");

                String cancelledId = ingress.submit(command(System.currentTimeMillis() + 30_000)).messageId();
                require(
                        ingress.cancel(cancelledId).status() == MessageStatus.CANCELLED,
                        "pending cancellation did not succeed");
                require(
                        ingress.query(cancelledId).status() == MessageStatus.CANCELLED,
                        "cancelled terminal state was not retained");

                String rebuiltId = ingress.submit(command(System.currentTimeMillis() + 2_000)).messageId();
                remoteWheel.stop();
                NettyTimeWheel rebuiltWheel = new NettyTimeWheel(TIME_WHEEL_ID, dueHandler);
                try {
                    int rebuilt = new TimeWheelRebuilder(storage).rebuildAndStart(rebuiltWheel);
                    require(rebuilt == 1, "restart rebuild did not load exactly the pending message");
                    awaitStatus(ingress, rebuiltId, MessageStatus.DELIVERED, Duration.ofSeconds(8));
                } finally {
                    rebuiltWheel.stop();
                }
            } finally {
                server.shutdownNow();
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

    private static SubmitCommand command(long deliverAt) {
        return new SubmitCommand(
                deliverAt,
                SinkType.HTTP,
                new HttpSinkConfig("https://example.invalid/callback", "POST", Map.of(), 1_000),
                new byte[] {1, 2, 3},
                "first-stage");
    }

    private static void awaitStatus(
            DefaultDelayMessageHandler handler,
            String messageId,
            MessageStatus expected,
            Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (handler.query(messageId).status() == expected) {
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
}
