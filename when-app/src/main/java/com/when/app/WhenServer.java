package com.when.app;

import com.when.cluster.etcd.EtcdMetadataClient;
import com.when.cluster.membership.EtcdClusterMembership;
import com.when.cluster.membership.NodeInfo;
import com.when.core.ClusterView;
import com.when.core.NodeEndpoint;
import com.when.core.SinkType;
import com.when.delivery.DefaultDueMessageHandler;
import com.when.delivery.DeliveryRecoveryWorker;
import com.when.delivery.ExpiredDeliveryRecovery;
import com.when.delivery.ExponentialRetryPolicy;
import com.when.ingress.application.DefaultDelayMessageHandler;
import com.when.ingress.application.DelayMessageForwarder;
import com.when.ingress.id.SnowflakeMessageIdGenerator;
import com.when.ingress.router.ConsistentHashRouter;
import com.when.plugin.storage.redis.RedisStorageConfig;
import com.when.plugin.storage.redis.RedisDeliveryStateStore;
import com.when.plugin.storage.redis.RedisStoragePlugin;
import com.when.sink.spi.SinkRegistry;
import com.when.timewheel.DefaultTimeWheelRegistry;
import com.when.timewheel.NettyTimeWheel;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

/** Executable single-node composition root used by the local integration harness. */
public final class WhenServer {
    private static final Logger LOGGER = Logger.getLogger(WhenServer.class.getName());

    private WhenServer() {
    }

    public static void main(String[] args) throws Exception {
        ApplicationConfig config = ApplicationConfig.fromEnvironment();
        String host = environment("WHEN_NODE_HOST", "127.0.0.1");
        String timeWheelId = config.timeWheelIds().get(0);
        if (config.timeWheelIds().size() != 1) {
            throw new IllegalStateException("single-node server requires exactly one time wheel");
        }

        RedisStoragePlugin storage = new RedisStoragePlugin(RedisStorageConfig.fromEnvironment());
        RedisDeliveryStateStore deliveryState =
                new RedisDeliveryStateStore(RedisStorageConfig.fromEnvironment());
        EtcdMetadataClient metadataClient = EtcdMetadataClient.fromEnvironment();
        EtcdClusterMembership membership = new EtcdClusterMembership(metadataClient);
        SinkRegistry sinks = SinkRegistry.load();
        sinks.require(SinkType.HTTP);
        sinks.require(SinkType.KAFKA);
        DefaultTimeWheelRegistry timeWheels = new DefaultTimeWheelRegistry();
        DefaultDueMessageHandler dueHandler = new DefaultDueMessageHandler(
                config.nodeId(),
                storage,
                sinks,
                new ExponentialRetryPolicy(),
                timeWheels,
                deliveryState,
                Clock.systemUTC(),
                DefaultDueMessageHandler.DEFAULT_LEASE_DURATION);
        NettyTimeWheel timeWheel = new NettyTimeWheel(timeWheelId, dueHandler);
        timeWheels.register(timeWheel);
        DeliveryRecoveryWorker recoveryWorker = new DeliveryRecoveryWorker(
                new ExpiredDeliveryRecovery(
                        storage, deliveryState, timeWheels, Clock.systemUTC(), 100),
                Duration.ofSeconds(10));
        NodeEndpoint endpoint = new NodeEndpoint(config.nodeId(), host, config.grpcPort());
        ClusterView singleNodeView = requestedTimeWheel -> timeWheelId.equals(requestedTimeWheel)
                ? Optional.of(endpoint)
                : Optional.empty();
        DefaultDelayMessageHandler handler = new DefaultDelayMessageHandler(
                config.nodeId(),
                new SnowflakeMessageIdGenerator(config.workerId()),
                new ConsistentHashRouter(List.of(timeWheelId)),
                singleNodeView,
                timeWheels,
                storage,
                DelayMessageForwarder.localOnly());
        WhenNode node = new WhenNode(
                config.grpcPort(),
                handler,
                storage,
                List.of(timeWheel),
                List.of(storage, metadataClient, membership, deliveryState, sinks, recoveryWorker));

        Runtime.getRuntime().addShutdownHook(new Thread(node::close, "when-server-shutdown"));
        try {
            membership.registerSelf(
                    new NodeInfo(config.nodeId(), host, config.grpcPort(), System.currentTimeMillis(), 0),
                    config.workerId());
            node.start();
            recoveryWorker.start();
            LOGGER.info(() -> "event=when_server_ready node_id=" + config.nodeId()
                    + " grpc_port=" + node.grpcPort() + " tw_id=" + timeWheelId);
            new CountDownLatch(1).await();
        } catch (Exception exception) {
            node.close();
            throw exception;
        }
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
