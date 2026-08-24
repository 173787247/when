package com.when.cluster.replica;

import com.when.cluster.etcd.EtcdKeys;
import com.when.cluster.etcd.EtcdMetadataClient;
import com.when.cluster.etcd.EtcdWatchEvent;
import com.when.cluster.etcd.MetadataJsonCodec;
import com.when.cluster.etcd.TimeWheelMetadata;
import com.when.core.TimeWheelAssignment;
import java.util.Objects;
import java.util.Optional;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** ETCD-backed assignment reader with version-fenced sync-state updates. */
public final class EtcdReplicaAssignmentStore implements ReplicaAssignmentStore, AutoCloseable {
    private static final int MAX_CAS_ATTEMPTS = 8;

    private final EtcdMetadataClient client;
    private final MetadataJsonCodec codec;
    private final Object stateLock = new Object();
    private final Map<String, TimeWheelAssignment> cache = new HashMap<>();
    private final List<EtcdWatchEvent> pendingEvents = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private boolean initializing;
    private EtcdMetadataClient.WatchHandle watch;

    public EtcdReplicaAssignmentStore(EtcdMetadataClient client) {
        this(client, new MetadataJsonCodec());
    }

    EtcdReplicaAssignmentStore(EtcdMetadataClient client, MetadataJsonCodec codec) {
        this.client = Objects.requireNonNull(client, "client");
        this.codec = Objects.requireNonNull(codec, "codec");
        start();
    }

    @Override
    public Optional<TimeWheelAssignment> current(String twId) {
        String id = requireText(twId, "twId");
        synchronized (stateLock) {
            requireOpen();
            return Optional.ofNullable(cache.get(id));
        }
    }

    @Override
    public boolean markSyncState(String twId, long assignmentVersion, String syncState) {
        String id = requireText(twId, "twId");
        String state = requireText(syncState, "syncState");
        if (assignmentVersion < 0) {
            throw new IllegalArgumentException("assignmentVersion must not be negative");
        }
        String key = EtcdKeys.timeWheel(id);
        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            Optional<String> encoded = client.get(key);
            if (encoded.isEmpty()) {
                return false;
            }
            TimeWheelMetadata current = codec.decodeTimeWheel(encoded.orElseThrow());
            if (current.assignmentVersion() != assignmentVersion) {
                return false;
            }
            if (current.syncState().equals(state)) {
                return true;
            }
            String replacement = codec.encodeTimeWheel(current.withSyncState(state));
            if (client.txnPutIfValue(key, encoded.orElseThrow(), replacement)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (stateLock) {
            if (watch != null) {
                watch.close();
                watch = null;
            }
            cache.clear();
            pendingEvents.clear();
        }
    }

    private void start() {
        synchronized (stateLock) {
            initializing = true;
            watch = client.watch(EtcdKeys.TIME_WHEELS_PREFIX, this::onEvent);
            try {
                Map<String, TimeWheelAssignment> loaded = new HashMap<>();
                client.getPrefix(EtcdKeys.TIME_WHEELS_PREFIX).forEach((key, value) -> {
                    String id = keySegment(key);
                    loaded.put(id, assignment(id, codec.decodeTimeWheel(value)));
                });
                cache.clear();
                cache.putAll(loaded);
                pendingEvents.forEach(this::applyEvent);
                pendingEvents.clear();
                initializing = false;
            } catch (RuntimeException exception) {
                initializing = false;
                watch.close();
                watch = null;
                throw exception;
            }
        }
    }

    private void onEvent(EtcdWatchEvent event) {
        synchronized (stateLock) {
            if (closed.get()) {
                return;
            }
            if (initializing) {
                pendingEvents.add(event);
            } else {
                applyEvent(event);
            }
        }
    }

    private void applyEvent(EtcdWatchEvent event) {
        String id = keySegment(event.key());
        if (event.type() == EtcdWatchEvent.Type.DELETE) {
            cache.remove(id);
        } else {
            cache.put(id, assignment(id, codec.decodeTimeWheel(event.value())));
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("replica assignment store is closed");
        }
    }

    private static String keySegment(String key) {
        if (key == null || !key.startsWith(EtcdKeys.TIME_WHEELS_PREFIX)) {
            throw new IllegalArgumentException("metadata key is outside time-wheel prefix");
        }
        String id = key.substring(EtcdKeys.TIME_WHEELS_PREFIX.length());
        if (id.isBlank() || id.indexOf('/') >= 0) {
            throw new IllegalArgumentException("metadata key has an invalid time-wheel id");
        }
        return id;
    }

    private static TimeWheelAssignment assignment(String twId, TimeWheelMetadata metadata) {
        return new TimeWheelAssignment(
                twId,
                metadata.master(),
                metadata.slave(),
                metadata.status(),
                metadata.syncState(),
                metadata.assignmentVersion());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
