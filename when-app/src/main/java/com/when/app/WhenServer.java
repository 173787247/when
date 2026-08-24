package com.when.app;

import com.when.cluster.etcd.EtcdMetadataClient;
import com.when.cluster.membership.EtcdClusterMembership;
import com.when.cluster.membership.NodeInfo;
import com.when.core.ClusterView;
import com.when.core.NodeEndpoint;
import com.when.core.StoragePlugin;
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
import com.when.plugin.storage.redis.RedisTraceContextStore;
import com.when.observability.DependencyHealthMonitor;
import com.when.observability.LogEvent;
import com.when.observability.ManagementConfig;
import com.when.observability.ManagementHttpServer;
import com.when.observability.MicrometerMetrics;
import com.when.observability.ObservedStoragePlugin;
import com.when.observability.ReadinessManager;
import com.when.observability.StructuredEventLogger;
import com.when.observability.TelemetryRuntime;
import com.when.observability.TraceOperations;
import com.when.sink.http.HttpSink;
import com.when.sink.kafka.KafkaSink;
import com.when.sink.spi.SinkRegistry;
import com.when.timewheel.DefaultTimeWheelRegistry;
import com.when.timewheel.NettyTimeWheel;
import com.when.timewheel.TimeWheelConfig;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.slf4j.bridge.SLF4JBridgeHandler;

/** Executable single-node composition root used by the local integration harness. */
public final class WhenServer {
    private WhenServer() {
    }

    public static void main(String[] args) throws Exception {
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
        ApplicationConfig config = ApplicationConfig.fromEnvironment();
        String host = environment("WHEN_NODE_HOST", "127.0.0.1");
        String timeWheelId = config.timeWheelIds().get(0);
        if (config.timeWheelIds().size() != 1) {
            throw new IllegalStateException("single-node server requires exactly one time wheel");
        }

        MicrometerMetrics metrics = new MicrometerMetrics();
        TelemetryRuntime telemetry = TelemetryRuntime.fromEnvironment(config.nodeId(), metrics);
        TraceOperations traces = telemetry.traces();
        ReadinessManager readiness = new ReadinessManager();
        StructuredEventLogger events = new StructuredEventLogger(WhenServer.class, "when", config.nodeId());
        ManagementHttpServer management = new ManagementHttpServer(
                ManagementConfig.fromEnvironment(), metrics, readiness).start();

        RedisStorageConfig redisConfig = RedisStorageConfig.fromEnvironment();
        RedisStoragePlugin rawStorage = new RedisStoragePlugin(redisConfig);
        StoragePlugin storage = new ObservedStoragePlugin(rawStorage, metrics, traces);
        RedisTraceContextStore traceContexts = new RedisTraceContextStore(redisConfig);
        RedisDeliveryStateStore deliveryState =
                new RedisDeliveryStateStore(redisConfig);
        EtcdMetadataClient metadataClient = EtcdMetadataClient.fromEnvironment(metrics, traces);
        EtcdClusterMembership membership = new EtcdClusterMembership(
                metadataClient,
                EtcdClusterMembership.DEFAULT_LEASE_TTL_SECONDS,
                EtcdClusterMembership.DEFAULT_HEARTBEAT_INTERVAL,
                metrics);
        DependencyHealthMonitor healthMonitor = new DependencyHealthMonitor(
                readiness,
                rawStorage::healthCheck,
                metadataClient::healthCheck,
                Duration.ofSeconds(2)).start();
        SinkRegistry sinks = new SinkRegistry(List.of(new HttpSink(traces), new KafkaSink(traces)));
        DefaultTimeWheelRegistry timeWheels = new DefaultTimeWheelRegistry();
        DefaultDueMessageHandler dueHandler = new DefaultDueMessageHandler(
                config.nodeId(),
                storage,
                sinks,
                new ExponentialRetryPolicy(),
                timeWheels,
                deliveryState,
                Clock.systemUTC(),
                DefaultDueMessageHandler.DEFAULT_LEASE_DURATION,
                metrics,
                traces,
                traceContexts,
                new StructuredEventLogger(DefaultDueMessageHandler.class, "when", config.nodeId()));
        NettyTimeWheel timeWheel = new NettyTimeWheel(
                timeWheelId,
                dueHandler,
                Clock.systemUTC(),
                TimeWheelConfig.defaults(),
                metrics,
                traces);
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
                DelayMessageForwarder.localOnly(),
                Clock.systemUTC(),
                () -> UUID.randomUUID().toString(),
                metrics,
                traces,
                traceContexts,
                new StructuredEventLogger(DefaultDelayMessageHandler.class, "when", config.nodeId()));
        WhenNode node = new WhenNode(
                config.grpcPort(),
                handler,
                storage,
                List.of(timeWheel),
                List.of(
                        management,
                        metrics,
                        telemetry,
                        traceContexts,
                        rawStorage,
                        metadataClient,
                        membership,
                        deliveryState,
                        sinks,
                        recoveryWorker,
                        healthMonitor),
                traces);

        Runtime.getRuntime().addShutdownHook(new Thread(node::close, "when-server-shutdown"));
        try {
            membership.registerSelf(
                    new NodeInfo(config.nodeId(), host, config.grpcPort(), System.currentTimeMillis(), 0),
                    config.workerId());
            readiness.registered(true);
            node.start();
            readiness.rolesRecovered(true);
            readiness.initialized(true);
            recoveryWorker.start();
            events.info(
                    LogEvent.SERVER_LIFECYCLE,
                    "When node is ready",
                    java.util.Map.of("status", "ready", "tw_id", timeWheelId));
            new CountDownLatch(1).await();
        } catch (Exception exception) {
            readiness.initialized(false);
            readiness.registered(false);
            readiness.rolesRecovered(false);
            node.close();
            throw exception;
        }
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
