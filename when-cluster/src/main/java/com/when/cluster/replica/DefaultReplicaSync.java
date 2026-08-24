package com.when.cluster.replica;

import com.when.core.RebuildResult;
import com.when.core.ReplicaOperation;
import com.when.core.ReplicaSync;
import com.when.core.SyncAck;
import com.when.core.TimeWheelAssignment;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.when.observability.Metrics;
import com.when.observability.WhenMetrics;

/**
 * Per-time-wheel bounded asynchronous replication pipeline.
 *
 * <p>{@link #sync(ReplicaOperation)} only validates and offers to an in-memory queue. Network
 * waiting and retry happen on that wheel's daemon worker, so submission/cancellation threads never
 * wait for the Slave.
 */
public final class DefaultReplicaSync implements ReplicaSync, AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(DefaultReplicaSync.class.getName());
    public static final int DEFAULT_QUEUE_CAPACITY = 10_000;
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final String localNodeId;
    private final ReplicaAssignmentStore assignments;
    private final ReplicaTransport transport;
    private final ReplicaOperationApplier applier;
    private final int queueCapacity;
    private final int maxAttempts;
    private final Duration attemptTimeout;
    private final ExecutorService metadataExecutor;
    private final Map<String, ChannelState> channels = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong retryCount = new AtomicLong();
    private final AtomicLong outOfSyncCount = new AtomicLong();
    private final AtomicLong rebuildCount = new AtomicLong();
    private final AtomicLong lastRebuildDurationMillis = new AtomicLong();
    private final Metrics metrics;

    public DefaultReplicaSync(
            String localNodeId,
            ReplicaAssignmentStore assignments,
            ReplicaTransport transport,
            ReplicaOperationApplier applier) {
        this(
                localNodeId,
                assignments,
                transport,
                applier,
                DEFAULT_QUEUE_CAPACITY,
                DEFAULT_MAX_ATTEMPTS,
                Duration.ofSeconds(2),
                Metrics.noop());
    }

    public DefaultReplicaSync(
            String localNodeId,
            ReplicaAssignmentStore assignments,
            ReplicaTransport transport,
            ReplicaOperationApplier applier,
            int queueCapacity,
            int maxAttempts,
            Duration attemptTimeout) {
        this(
                localNodeId,
                assignments,
                transport,
                applier,
                queueCapacity,
                maxAttempts,
                attemptTimeout,
                Metrics.noop());
    }

    public DefaultReplicaSync(
            String localNodeId,
            ReplicaAssignmentStore assignments,
            ReplicaTransport transport,
            ReplicaOperationApplier applier,
            int queueCapacity,
            int maxAttempts,
            Duration attemptTimeout,
            Metrics metrics) {
        this.localNodeId = requireText(localNodeId, "localNodeId");
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.applier = Objects.requireNonNull(applier, "applier");
        if (queueCapacity <= 0 || maxAttempts <= 0) {
            throw new IllegalArgumentException("queueCapacity and maxAttempts must be positive");
        }
        this.queueCapacity = queueCapacity;
        this.maxAttempts = maxAttempts;
        this.attemptTimeout = Objects.requireNonNull(attemptTimeout, "attemptTimeout");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        if (attemptTimeout.isZero() || attemptTimeout.isNegative()) {
            throw new IllegalArgumentException("attemptTimeout must be positive");
        }
        this.metadataExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "when-replica-metadata");
            thread.setDaemon(true);
            return thread;
        });
        this.metrics.gauge(
                WhenMetrics.REPLICA_SYNC_QUEUE_SIZE,
                () -> channels.values().stream().mapToInt(channel -> channel.queue.size()).sum(),
                "node_id", localNodeId);
    }

    @Override
    public CompletionStage<SyncAck> sync(ReplicaOperation operation) {
        Objects.requireNonNull(operation, "operation");
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("replica sync is closed"));
        }
        TimeWheelAssignment assignment = assignments.current(operation.twId())
                .orElseThrow(() -> new IllegalStateException("time wheel assignment is unavailable"));
        if (!assignment.isMaster(localNodeId)
                || assignment.assignmentVersion() != operation.assignmentVersion()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("local node is not the fenced Master"));
        }

        ChannelState channel = channels.computeIfAbsent(
                operation.twId(), id -> new ChannelState(id, queueCapacity));
        Pending pending;
        boolean rejected = false;
        synchronized (channel) {
            channel.ensureWorkerStarted();
            if (channel.assignmentVersion != operation.assignmentVersion()) {
                channel.reset(operation.assignmentVersion());
            }
            CompletableFuture<SyncAck> duplicate = channel.operationFutures.get(operation.operationId());
            if (duplicate != null) {
                return duplicate;
            }
            if (operation.sequence() != channel.lastEnqueuedSequence + 1) {
                channel.outOfSync = true;
                rejected = true;
                pending = new Pending(operation);
                pending.acknowledgement.completeExceptionally(
                        new IllegalStateException("non-contiguous Master sequence"));
            } else {
                pending = new Pending(operation);
                if (!channel.queue.offer(pending)) {
                    channel.outOfSync = true;
                    rejected = true;
                    pending.acknowledgement.completeExceptionally(
                            new IllegalStateException("replica sync queue is full"));
                } else {
                    channel.lastEnqueuedSequence = operation.sequence();
                    channel.operationFutures.put(operation.operationId(), pending.acknowledgement);
                    trim(channel.operationFutures, queueCapacity);
                }
            }
        }
        if (rejected) {
            markOutOfSync(
                    operation.twId(), operation.assignmentVersion(), "replica queue or sequence rejected");
        }
        return pending.acknowledgement;
    }

    @Override
    public void markOutOfSync(String twId, long assignmentVersion, String reason) {
        String id = requireText(twId, "twId");
        String safeReason = safeReason(requireText(reason, "reason"));
        long lastAcknowledged = markLocalOutOfSync(id);
        if (!closed.get()) {
            metadataExecutor.execute(
                    () -> persistOutOfSync(id, assignmentVersion, lastAcknowledged, safeReason));
        }
    }

    private long markLocalOutOfSync(String id) {
        ChannelState channel = channels.get(id);
        if (channel != null) {
            synchronized (channel) {
                channel.outOfSync = true;
            }
        }
        return channel == null ? 0 : lastAcknowledgedSequence(id);
    }

    private void persistOutOfSync(
            String id, long assignmentVersion, long lastAcknowledged, String reason) {
        assignments.markSyncState(id, assignmentVersion, "out_of_sync");
        outOfSyncCount.incrementAndGet();
        LOGGER.log(
                Level.WARNING,
                "operation=replica_sync status=out_of_sync tw_id={0} assignment_version={1}"
                        + " last_ack_sequence={2} reason={3}",
                new Object[] {id, assignmentVersion, lastAcknowledged, reason});
    }

    @Override
    public CompletionStage<RebuildResult> rebuildFromRedis(
            String twId, long assignmentVersion, long startSequence) {
        long startedAt = System.nanoTime();
        CompletionStage<RebuildResult> rebuild =
                applier.rebuild(twId, assignmentVersion, startSequence);
        ChannelState channel = channels.get(twId);
        List<CompletableFuture<SyncAck>> catchUp = new ArrayList<>();
        if (channel != null) {
            synchronized (channel) {
                if (channel.assignmentVersion != assignmentVersion) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("replica assignment changed before rebuild"));
                }
                channel.queue.forEach(item -> catchUp.add(item.acknowledgement));
                channel.outOfSync = false;
                channel.notifyAll();
            }
        }
        CompletionStage<RebuildResult> caughtUp = rebuild.thenCompose(result ->
                CompletableFuture.allOf(catchUp.toArray(CompletableFuture[]::new))
                        .thenApply(ignored -> new RebuildResult(
                                result.twId(),
                                result.assignmentVersion(),
                                applier.lastAppliedSequence(result.twId()),
                                result.rebuiltMessages())));
        return caughtUp
                .whenComplete((ignored, failure) -> {
                    rebuildCount.incrementAndGet();
                    lastRebuildDurationMillis.set(
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
                    if (failure != null && channel != null) {
                        markLocalOutOfSync(twId);
                    }
                    metrics.incr(
                            WhenMetrics.REPLICA_REBUILD,
                            "result", failure == null ? "success" : "failure");
                });
    }

    public int queueSize(String twId) {
        ChannelState channel = channels.get(requireText(twId, "twId"));
        return channel == null ? 0 : channel.queue.size();
    }

    public long lastAcknowledgedSequence(String twId) {
        ChannelState channel = channels.get(requireText(twId, "twId"));
        if (channel == null) {
            return 0;
        }
        synchronized (channel) {
            return channel.lastAcknowledgedSequence;
        }
    }

    public long retryCount() {
        return retryCount.get();
    }

    public long outOfSyncCount() {
        return outOfSyncCount.get();
    }

    public long rebuildCount() {
        return rebuildCount.get();
    }

    public long lastRebuildDurationMillis() {
        return lastRebuildDurationMillis.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        channels.values().forEach(ChannelState::close);
        channels.clear();
        metadataExecutor.shutdownNow();
    }

    private void run(ChannelState channel) {
        while (!closed.get() && !channel.stopped.get()) {
            try {
                synchronized (channel) {
                    while (channel.outOfSync && !closed.get() && !channel.stopped.get()) {
                        channel.wait(200L);
                    }
                }
                Pending pending = channel.queue.poll(200, TimeUnit.MILLISECONDS);
                if (pending == null) {
                    continue;
                }
                sendWithRetry(channel, pending);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void sendWithRetry(ChannelState channel, Pending pending) {
        synchronized (channel) {
            if (channel.assignmentVersion != pending.operation.assignmentVersion()) {
                pending.acknowledgement.completeExceptionally(
                        new IllegalStateException("replica assignment changed"));
                return;
            }
        }
        RuntimeException terminal = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (attempt > 1) {
                retryCount.incrementAndGet();
            }
            try {
                SyncAck ack = transport.send(pending.operation)
                        .toCompletableFuture()
                        .get(attemptTimeout.toMillis(), TimeUnit.MILLISECONDS);
                validateAck(pending.operation, ack);
                synchronized (channel) {
                    if (channel.assignmentVersion != pending.operation.assignmentVersion()) {
                        pending.acknowledgement.completeExceptionally(
                                new IllegalStateException("replica assignment changed"));
                        return;
                    }
                    channel.lastAcknowledgedSequence = Math.max(
                            channel.lastAcknowledgedSequence, ack.lastAppliedSequence());
                }
                pending.acknowledgement.complete(ack);
                return;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                terminal = new IllegalStateException("replica sync interrupted", exception);
                break;
            } catch (ExecutionException | TimeoutException | RuntimeException exception) {
                terminal = new IllegalStateException("replica sync attempt failed", exception);
            }
        }
        RuntimeException failure = terminal == null
                ? new IllegalStateException("replica sync failed")
                : terminal;
        synchronized (channel) {
            if (channel.assignmentVersion != pending.operation.assignmentVersion()) {
                pending.acknowledgement.completeExceptionally(
                        new IllegalStateException("replica assignment changed"));
                return;
            }
        }
        long lastAcknowledged = markLocalOutOfSync(pending.operation.twId());
        persistOutOfSync(
                pending.operation.twId(),
                pending.operation.assignmentVersion(),
                lastAcknowledged,
                failure.getClass().getSimpleName());
        pending.acknowledgement.completeExceptionally(failure);
    }

    private static void validateAck(ReplicaOperation operation, SyncAck ack) {
        Objects.requireNonNull(ack, "replica returned a null acknowledgement");
        if (!operation.twId().equals(ack.twId())
                || operation.assignmentVersion() != ack.assignmentVersion()
                || ack.lastAppliedSequence() < operation.sequence()) {
            throw new IllegalStateException("replica acknowledgement does not match operation");
        }
    }

    private static <K, V> void trim(LinkedHashMap<K, V> map, int maximumSize) {
        while (map.size() > maximumSize) {
            var iterator = map.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private final class ChannelState {
        private final String twId;
        private final ArrayBlockingQueue<Pending> queue;
        private final LinkedHashMap<String, CompletableFuture<SyncAck>> operationFutures =
                new LinkedHashMap<>();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private long assignmentVersion = -1;
        private long lastEnqueuedSequence;
        private long lastAcknowledgedSequence;
        private boolean outOfSync;
        private Thread worker;

        private ChannelState(String twId, int capacity) {
            this.twId = twId;
            this.queue = new ArrayBlockingQueue<>(capacity);
        }

        private synchronized void ensureWorkerStarted() {
            if (worker != null) {
                return;
            }
            worker = new Thread(() -> run(this), "when-replica-sync-" + safe(twId));
            worker.setDaemon(true);
            worker.start();
        }

        private void reset(long newAssignmentVersion) {
            IllegalStateException changed = new IllegalStateException("replica assignment changed");
            List<Pending> abandoned = new ArrayList<>();
            queue.drainTo(abandoned);
            abandoned.forEach(item -> item.acknowledgement.completeExceptionally(changed));
            operationFutures.clear();
            assignmentVersion = newAssignmentVersion;
            lastEnqueuedSequence = 0;
            lastAcknowledgedSequence = 0;
            outOfSync = false;
            notifyAll();
        }

        private void close() {
            stopped.set(true);
            synchronized (this) {
                notifyAll();
            }
            Thread current = worker;
            if (current != null) {
                current.interrupt();
            }
            List<Pending> abandoned = new ArrayList<>();
            queue.drainTo(abandoned);
            IllegalStateException failure = new IllegalStateException("replica sync is closed");
            abandoned.forEach(item -> item.acknowledgement.completeExceptionally(failure));
        }
    }

    private static final class Pending {
        private final ReplicaOperation operation;
        private final CompletableFuture<SyncAck> acknowledgement = new CompletableFuture<>();

        private Pending(ReplicaOperation operation) {
            this.operation = operation;
        }
    }

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String safeReason(String value) {
        String sanitized = safe(value);
        return sanitized.length() <= 80 ? sanitized : sanitized.substring(0, 80);
    }
}
