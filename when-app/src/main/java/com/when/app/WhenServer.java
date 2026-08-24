package com.when.app;

import com.when.cluster.etcd.EtcdMetadataClient;
import com.when.cluster.membership.EtcdClusterMembership;
import com.when.cluster.membership.NodeInfo;
import com.when.core.ClusterView;
import com.when.core.NodeEndpoint;
import com.when.ingress.application.DefaultDelayMessageHandler;
import com.when.ingress.application.DelayMessageForwarder;
import com.when.ingress.id.SnowflakeMessageIdGenerator;
import com.when.ingress.router.ConsistentHashRouter;
import com.when.plugin.storage.redis.RedisStorageConfig;
import com.when.plugin.storage.redis.RedisStoragePlugin;
import com.when.timewheel.DefaultTimeWheelRegistry;
import com.when.timewheel.NettyTimeWheel;
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
        EtcdMetadataClient metadataClient = EtcdMetadataClient.fromEnvironment();
        EtcdClusterMembership membership = new EtcdClusterMembership(metadataClient);
        NettyTimeWheel timeWheel = new NettyTimeWheel(timeWheelId, new LogOnlyDueMessageHandler(storage));
        NodeEndpoint endpoint = new NodeEndpoint(config.nodeId(), host, config.grpcPort());
        ClusterView singleNodeView = requestedTimeWheel -> timeWheelId.equals(requestedTimeWheel)
                ? Optional.of(endpoint)
                : Optional.empty();
        DefaultDelayMessageHandler handler = new DefaultDelayMessageHandler(
                config.nodeId(),
                new SnowflakeMessageIdGenerator(config.workerId()),
                new ConsistentHashRouter(List.of(timeWheelId)),
                singleNodeView,
                new DefaultTimeWheelRegistry(List.of(timeWheel)),
                storage,
                DelayMessageForwarder.localOnly());
        WhenNode node = new WhenNode(
                config.grpcPort(), handler, storage, List.of(timeWheel), List.of(storage, metadataClient, membership));

        Runtime.getRuntime().addShutdownHook(new Thread(node::close, "when-server-shutdown"));
        try {
            membership.registerSelf(
                    new NodeInfo(config.nodeId(), host, config.grpcPort(), System.currentTimeMillis(), 0),
                    config.workerId());
            node.start();
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
