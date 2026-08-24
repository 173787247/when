package com.when.cluster.membership;

import com.when.cluster.etcd.EtcdKeys;
import com.when.cluster.etcd.EtcdMetadataClient;
import com.when.cluster.etcd.EtcdWatchEvent;
import com.when.cluster.etcd.MetadataJsonCodec;
import com.when.cluster.etcd.TimeWheelMetadata;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.when.observability.Metrics;
import com.when.observability.WhenMetrics;

/** ETCD-backed node registration, lease heartbeat, member watch and Controller election. */
public final class EtcdClusterMembership implements ClusterMembership {
    public static final long DEFAULT_LEASE_TTL_SECONDS = 6;
    public static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(2);

    private static final Logger LOGGER = Logger.getLogger(EtcdClusterMembership.class.getName());

    private final EtcdMetadataClient client;
    private final ClusterMetadataCodec clusterCodec;
    private final MetadataJsonCodec metadataCodec;
    private final long leaseTtlSeconds;
    private final Duration heartbeatInterval;
    private final ScheduledExecutorService heartbeatExecutor;
    private final ScheduledExecutorService electionExecutor;
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Metrics metrics;

    private volatile NodeInfo self;
    private volatile int workerId = -1;
    private volatile long leaseId;
    private volatile boolean controller;
    private volatile ControllerSnapshot controllerSnapshot;
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile EtcdMetadataClient.WatchHandle memberWatch;
    private volatile EtcdMetadataClient.WatchHandle controllerWatch;

    public EtcdClusterMembership(EtcdMetadataClient client) {
        this(client, DEFAULT_LEASE_TTL_SECONDS, DEFAULT_HEARTBEAT_INTERVAL, Metrics.noop());
    }

    public EtcdClusterMembership(
            EtcdMetadataClient client,
            long leaseTtlSeconds,
            Duration heartbeatInterval) {
        this(client, leaseTtlSeconds, heartbeatInterval, Metrics.noop());
    }

    public EtcdClusterMembership(
            EtcdMetadataClient client,
            long leaseTtlSeconds,
            Duration heartbeatInterval,
            Metrics metrics) {
        this.client = Objects.requireNonNull(client, "client");
        if (leaseTtlSeconds <= 0) {
            throw new IllegalArgumentException("leaseTtlSeconds must be positive");
        }
        this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
        if (heartbeatInterval.isZero() || heartbeatInterval.isNegative()
                || heartbeatInterval.compareTo(Duration.ofSeconds(leaseTtlSeconds)) >= 0) {
            throw new IllegalArgumentException("heartbeatInterval must be positive and below lease TTL");
        }
        this.leaseTtlSeconds = leaseTtlSeconds;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clusterCodec = new ClusterMetadataCodec();
        this.metadataCodec = new MetadataJsonCodec();
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(
                runnable -> daemonThread(runnable, "when-etcd-heartbeat"));
        this.electionExecutor = Executors.newSingleThreadScheduledExecutor(
                runnable -> daemonThread(runnable, "when-controller-election"));
    }

    @Override
    public void registerSelf(NodeInfo node, int newWorkerId) {
        Objects.requireNonNull(node, "self");
        EtcdKeys.worker(newWorkerId);
        synchronized (lifecycleLock) {
            requireOpen();
            if (leaseId != 0) {
                throw new IllegalStateException("this membership is already registered");
            }
            long newLeaseId = client.grantLease(leaseTtlSeconds);
            boolean registered = false;
            try {
                registered = client.txnRegisterNodeAndWorker(
                        node.nodeId(), clusterCodec.encodeNode(node), newWorkerId, newLeaseId);
                if (!registered) {
                    throw new IllegalStateException("node ID or worker ID is already in use");
                }
                self = node;
                workerId = newWorkerId;
                leaseId = newLeaseId;
                heartbeatTask = heartbeatExecutor.scheduleWithFixedDelay(
                        this::heartbeat,
                        heartbeatInterval.toMillis(),
                        heartbeatInterval.toMillis(),
                        TimeUnit.MILLISECONDS);
            } finally {
                if (!registered) {
                    client.revokeLease(newLeaseId);
                }
            }
        }
    }

    @Override
    public void deregisterSelf() {
        long registeredLease;
        synchronized (lifecycleLock) {
            registeredLease = leaseId;
            if (registeredLease == 0) {
                return;
            }
            leaseId = 0;
            controller = false;
            controllerSnapshot = null;
            ScheduledFuture<?> task = heartbeatTask;
            heartbeatTask = null;
            if (task != null) {
                task.cancel(false);
            }
            closeWatch(controllerWatch);
            controllerWatch = null;
        }
        try {
            client.revokeLease(registeredLease);
        } finally {
            self = null;
            workerId = -1;
        }
    }

    @Override
    public boolean tryBecomeController() {
        requireRegistered();
        synchronized (lifecycleLock) {
            if (controllerWatch == null) {
                controllerWatch = client.watchKey(
                        EtcdKeys.CONTROLLER, 0, this::onControllerEvent, ignored -> { });
            }
        }
        return attemptControllerClaim();
    }

    @Override
    public void watchMembers(MemberEventHandler handler) {
        Objects.requireNonNull(handler, "handler");
        synchronized (lifecycleLock) {
            requireOpen();
            closeWatch(memberWatch);
            memberWatch = client.watch(EtcdKeys.NODES_PREFIX, event -> {
                String nodeId = keySegment(event.key(), EtcdKeys.NODES_PREFIX);
                if (event.type() == EtcdWatchEvent.Type.PUT) {
                    NodeInfo node = clusterCodec.decodeNode(nodeId, event.value());
                    handler.onEvent(new MemberEvent(
                            MemberEvent.Type.PUT, nodeId, Optional.of(node)));
                } else {
                    handler.onEvent(new MemberEvent(
                            MemberEvent.Type.DELETE, nodeId, Optional.empty()));
                }
            });
        }
    }

    @Override
    public List<NodeInfo> listNodes() {
        List<NodeInfo> nodes = new ArrayList<>();
        client.getPrefix(EtcdKeys.NODES_PREFIX).forEach((key, value) -> {
            String nodeId = keySegment(key, EtcdKeys.NODES_PREFIX);
            nodes.add(clusterCodec.decodeNode(nodeId, value));
        });
        nodes.sort(Comparator.comparing(NodeInfo::nodeId));
        return List.copyOf(nodes);
    }

    @Override
    public Optional<String> currentController() {
        return client.get(EtcdKeys.CONTROLLER);
    }

    @Override
    public OptionalLong currentControllerTerm() {
        return client.getValue(EtcdKeys.CONTROLLER)
                .map(value -> OptionalLong.of(value.modRevision()))
                .orElseGet(OptionalLong::empty);
    }

    public boolean isController() {
        return controller;
    }

    public Optional<ControllerSnapshot> controllerSnapshot() {
        return Optional.ofNullable(controllerSnapshot);
    }

    public int workerId() {
        return workerId;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        deregisterSelf();
        synchronized (lifecycleLock) {
            closeWatch(memberWatch);
            memberWatch = null;
        }
        heartbeatExecutor.shutdownNow();
        electionExecutor.shutdownNow();
    }

    private void heartbeat() {
        long currentLease = leaseId;
        if (currentLease == 0) {
            return;
        }
        try {
            client.keepAliveOnce(currentLease);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING,
                    "operation=etcd_heartbeat status=failed error_type={0}",
                    e.getClass().getSimpleName());
        }
    }

    private boolean attemptControllerClaim() {
        NodeInfo currentSelf = self;
        long currentLease = leaseId;
        if (currentSelf == null || currentLease == 0 || closed.get()) {
            controller = false;
            metrics.incr(WhenMetrics.CONTROLLER_ELECTIONS, "result", "ineligible");
            return false;
        }
        boolean owns = false;
        // A Controller can disappear between a failed CAS and the following read. A bounded
        // retry closes that watch-registration race without turning this into a busy loop.
        for (int attempt = 0; attempt < 2 && !owns; attempt++) {
            boolean won = client.txnPutIfAbsent(
                    EtcdKeys.CONTROLLER, currentSelf.nodeId(), currentLease);
            Optional<String> observed = currentController();
            owns = won || observed.filter(currentSelf.nodeId()::equals).isPresent();
            if (observed.isPresent() && !owns) {
                break;
            }
        }
        controller = owns;
        if (owns) {
            controllerSnapshot = loadControllerSnapshot();
        }
        metrics.incr(
                WhenMetrics.CONTROLLER_ELECTIONS,
                "result", owns ? "won" : "lost");
        return owns;
    }

    private ControllerSnapshot loadControllerSnapshot() {
        Map<String, TimeWheelMetadata> wheels = new LinkedHashMap<>();
        client.getPrefix(EtcdKeys.TIME_WHEELS_PREFIX).forEach((key, value) -> {
            String id = keySegment(key, EtcdKeys.TIME_WHEELS_PREFIX);
            wheels.put(id, metadataCodec.decodeTimeWheel(value));
        });
        return new ControllerSnapshot(listNodes(), wheels);
    }

    private void onControllerEvent(EtcdWatchEvent event) {
        NodeInfo currentSelf = self;
        if (event.type() == EtcdWatchEvent.Type.PUT) {
            controller = currentSelf != null && currentSelf.nodeId().equals(event.value());
            return;
        }
        controller = false;
        if (leaseId != 0 && !closed.get()) {
            electionExecutor.execute(() -> {
                try {
                    attemptControllerClaim();
                } catch (RuntimeException e) {
                    LOGGER.log(Level.WARNING,
                            "operation=controller_election status=failed error_type={0}",
                            e.getClass().getSimpleName());
                }
            });
        }
    }

    private void requireRegistered() {
        requireOpen();
        if (self == null || leaseId == 0) {
            throw new IllegalStateException("node must be registered before election");
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("membership is closed");
        }
    }

    private static String keySegment(String key, String prefix) {
        if (key == null || !key.startsWith(prefix)) {
            throw new IllegalArgumentException("metadata key is outside expected prefix");
        }
        String segment = key.substring(prefix.length());
        if (segment.isBlank() || segment.indexOf('/') >= 0) {
            throw new IllegalArgumentException("metadata key has an invalid segment");
        }
        return segment;
    }

    private static Thread daemonThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void closeWatch(EtcdMetadataClient.WatchHandle watch) {
        if (watch != null) {
            watch.close();
        }
    }
}
