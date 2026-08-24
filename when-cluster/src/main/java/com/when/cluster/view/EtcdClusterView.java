package com.when.cluster.view;

import com.when.cluster.etcd.EtcdKeys;
import com.when.cluster.etcd.EtcdMetadataClient;
import com.when.cluster.etcd.EtcdWatchEvent;
import com.when.cluster.etcd.MetadataJsonCodec;
import com.when.cluster.etcd.TimeWheelMetadata;
import com.when.cluster.membership.ClusterMetadataCodec;
import com.when.cluster.membership.NodeInfo;
import com.when.core.ClusterView;
import com.when.core.NodeEndpoint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Watch-backed read-only view of online nodes and persistent time-wheel placement.
 *
 * <p>This class only reflects ETCD facts. In particular, it never promotes a replica or chooses a
 * substitute when the configured Master is offline.</p>
 */
public final class EtcdClusterView implements ClusterView, AutoCloseable {
    private final EtcdMetadataClient client;
    private final ClusterMetadataCodec clusterCodec = new ClusterMetadataCodec();
    private final MetadataJsonCodec metadataCodec = new MetadataJsonCodec();
    private final Object stateLock = new Object();
    private final Map<String, NodeInfo> nodes = new HashMap<>();
    private final Map<String, TimeWheelMetadata> timeWheels = new HashMap<>();
    private final List<EtcdWatchEvent> pendingNodeEvents = new ArrayList<>();
    private final List<EtcdWatchEvent> pendingTimeWheelEvents = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    private boolean initializing;
    private boolean started;
    private EtcdMetadataClient.WatchHandle nodeWatch;
    private EtcdMetadataClient.WatchHandle timeWheelWatch;

    public EtcdClusterView(EtcdMetadataClient client) {
        this.client = Objects.requireNonNull(client, "client");
        start();
    }

    /** Starts both watches and loads a snapshot. Calling this more than once is harmless. */
    public void start() {
        synchronized (stateLock) {
            requireOpen();
            if (started) {
                return;
            }
            initializing = true;
            nodeWatch = client.watch(EtcdKeys.NODES_PREFIX, this::onNodeEvent);
            try {
                timeWheelWatch = client.watch(
                        EtcdKeys.TIME_WHEELS_PREFIX, this::onTimeWheelEvent);
                Map<String, NodeInfo> loadedNodes = loadNodes();
                Map<String, TimeWheelMetadata> loadedWheels = loadTimeWheels();
                nodes.clear();
                nodes.putAll(loadedNodes);
                timeWheels.clear();
                timeWheels.putAll(loadedWheels);
                pendingNodeEvents.forEach(this::applyNodeEvent);
                pendingTimeWheelEvents.forEach(this::applyTimeWheelEvent);
                pendingNodeEvents.clear();
                pendingTimeWheelEvents.clear();
                initializing = false;
                started = true;
            } catch (RuntimeException e) {
                initializing = false;
                closeWatch(nodeWatch);
                closeWatch(timeWheelWatch);
                nodeWatch = null;
                timeWheelWatch = null;
                throw e;
            }
        }
    }

    @Override
    public Optional<NodeEndpoint> masterOf(String timeWheelId) {
        if (timeWheelId == null || timeWheelId.isBlank()) {
            throw new IllegalArgumentException("timeWheelId must not be blank");
        }
        synchronized (stateLock) {
            TimeWheelMetadata placement = timeWheels.get(timeWheelId);
            if (placement == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(nodes.get(placement.master())).map(NodeInfo::endpoint);
        }
    }

    public List<NodeInfo> nodes() {
        synchronized (stateLock) {
            return nodes.values().stream()
                    .sorted(java.util.Comparator.comparing(NodeInfo::nodeId))
                    .toList();
        }
    }

    public Map<String, TimeWheelMetadata> timeWheels() {
        synchronized (stateLock) {
            return Map.copyOf(timeWheels);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (stateLock) {
            closeWatch(nodeWatch);
            closeWatch(timeWheelWatch);
            nodeWatch = null;
            timeWheelWatch = null;
            started = false;
            nodes.clear();
            timeWheels.clear();
            pendingNodeEvents.clear();
            pendingTimeWheelEvents.clear();
        }
    }

    private Map<String, NodeInfo> loadNodes() {
        Map<String, NodeInfo> loaded = new HashMap<>();
        client.getPrefix(EtcdKeys.NODES_PREFIX).forEach((key, value) -> {
            String id = keySegment(key, EtcdKeys.NODES_PREFIX);
            loaded.put(id, clusterCodec.decodeNode(id, value));
        });
        return loaded;
    }

    private Map<String, TimeWheelMetadata> loadTimeWheels() {
        Map<String, TimeWheelMetadata> loaded = new HashMap<>();
        client.getPrefix(EtcdKeys.TIME_WHEELS_PREFIX).forEach((key, value) -> {
            String id = keySegment(key, EtcdKeys.TIME_WHEELS_PREFIX);
            loaded.put(id, metadataCodec.decodeTimeWheel(value));
        });
        return loaded;
    }

    private void onNodeEvent(EtcdWatchEvent event) {
        synchronized (stateLock) {
            if (initializing) {
                pendingNodeEvents.add(event);
            } else if (started && !closed.get()) {
                applyNodeEvent(event);
            }
        }
    }

    private void onTimeWheelEvent(EtcdWatchEvent event) {
        synchronized (stateLock) {
            if (initializing) {
                pendingTimeWheelEvents.add(event);
            } else if (started && !closed.get()) {
                applyTimeWheelEvent(event);
            }
        }
    }

    private void applyNodeEvent(EtcdWatchEvent event) {
        String id = keySegment(event.key(), EtcdKeys.NODES_PREFIX);
        if (event.type() == EtcdWatchEvent.Type.DELETE) {
            nodes.remove(id);
        } else {
            nodes.put(id, clusterCodec.decodeNode(id, event.value()));
        }
    }

    private void applyTimeWheelEvent(EtcdWatchEvent event) {
        String id = keySegment(event.key(), EtcdKeys.TIME_WHEELS_PREFIX);
        if (event.type() == EtcdWatchEvent.Type.DELETE) {
            timeWheels.remove(id);
        } else {
            timeWheels.put(id, metadataCodec.decodeTimeWheel(event.value()));
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("cluster view is closed");
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

    private static void closeWatch(EtcdMetadataClient.WatchHandle watch) {
        if (watch != null) {
            watch.close();
        }
    }
}
