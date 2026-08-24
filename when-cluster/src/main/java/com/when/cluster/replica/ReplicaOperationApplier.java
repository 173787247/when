package com.when.cluster.replica;

import com.when.core.Message;
import com.when.core.MessageStatus;
import com.when.core.OperationType;
import com.when.core.RebuildResult;
import com.when.core.ReplicaOperation;
import com.when.core.StoragePlugin;
import com.when.core.SyncAck;
import com.when.core.TimeWheel;
import com.when.core.TimeWheelAssignment;
import com.when.core.TimeWheelRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Slave-side ordered/idempotent operation executor.
 *
 * <p>During a Redis rebuild, arriving operations are retained in a bounded per-wheel buffer and
 * their acknowledgements complete only after snapshot catch-up. A gap, overflow, or role/version
 * mismatch never gets guessed around.
 */
public final class ReplicaOperationApplier implements AutoCloseable {
    public static final int DEFAULT_BUFFER_CAPACITY = 10_000;

    private final String localNodeId;
    private final TimeWheelRegistry timeWheels;
    private final StoragePlugin storage;
    private final ReplicaAssignmentStore assignments;
    private final int bufferCapacity;
    private final Map<String, WheelState> states = new ConcurrentHashMap<>();
    private final ExecutorService rebuildExecutor;

    public ReplicaOperationApplier(
            String localNodeId,
            TimeWheelRegistry timeWheels,
            StoragePlugin storage,
            ReplicaAssignmentStore assignments) {
        this(localNodeId, timeWheels, storage, assignments, DEFAULT_BUFFER_CAPACITY);
    }

    public ReplicaOperationApplier(
            String localNodeId,
            TimeWheelRegistry timeWheels,
            StoragePlugin storage,
            ReplicaAssignmentStore assignments,
            int bufferCapacity) {
        this.localNodeId = requireText(localNodeId, "localNodeId");
        this.timeWheels = Objects.requireNonNull(timeWheels, "timeWheels");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        if (bufferCapacity <= 0) {
            throw new IllegalArgumentException("bufferCapacity must be positive");
        }
        this.bufferCapacity = bufferCapacity;
        this.rebuildExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "when-replica-rebuild-" + safe(localNodeId));
            thread.setDaemon(true);
            return thread;
        });
    }

    public CompletionStage<SyncAck> apply(ReplicaOperation operation) {
        Objects.requireNonNull(operation, "operation");
        TimeWheelAssignment assignment = requireSlaveAssignment(
                operation.twId(), operation.assignmentVersion());
        WheelState state = states.computeIfAbsent(operation.twId(), ignored -> new WheelState());
        boolean markOutOfSync = false;
        CompletableFuture<SyncAck> result;
        synchronized (state) {
            resetForNewAssignment(state, assignment.assignmentVersion());
            if (state.outOfSync) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("replica is out of sync"));
            }
            if (state.rebuilding) {
                BufferedOperation duplicate = state.bufferedByOperation.get(operation.operationId());
                if (duplicate != null) {
                    return duplicate.acknowledgement;
                }
                if (state.bufferedByOperation.size() >= bufferCapacity) {
                    state.outOfSync = true;
                    markOutOfSync = true;
                    result = CompletableFuture.failedFuture(
                            new IllegalStateException("replica rebuild buffer is full"));
                } else {
                    BufferedOperation buffered = new BufferedOperation(operation);
                    state.bufferedByOperation.put(operation.operationId(), buffered);
                    result = buffered.acknowledgement;
                }
            } else {
                try {
                    result = CompletableFuture.completedFuture(applyNow(state, operation));
                } catch (SequenceGapException exception) {
                    state.outOfSync = true;
                    markOutOfSync = true;
                    result = CompletableFuture.failedFuture(exception);
                }
            }
        }
        if (markOutOfSync) {
            assignments.markSyncState(operation.twId(), operation.assignmentVersion(), "out_of_sync");
        }
        return result;
    }

    public CompletionStage<RebuildResult> rebuild(
            String twId, long assignmentVersion, long startSequence) {
        String id = requireText(twId, "twId");
        if (startSequence < 0) {
            throw new IllegalArgumentException("startSequence must not be negative");
        }
        requireSlaveAssignment(id, assignmentVersion);
        WheelState state = states.computeIfAbsent(id, ignored -> new WheelState());
        List<String> staleIds;
        synchronized (state) {
            resetForNewAssignment(state, assignmentVersion);
            if (state.rebuilding) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("replica rebuild is already running"));
            }
            state.rebuilding = true;
            state.outOfSync = false;
            staleIds = List.copyOf(state.indexedMessageIds.keySet());
        }

        return CompletableFuture.supplyAsync(
                () -> performRebuild(id, assignmentVersion, startSequence, state, staleIds),
                rebuildExecutor);
    }

    private RebuildResult performRebuild(
            String id,
            long assignmentVersion,
            long startSequence,
            WheelState state,
            List<String> staleIds) {
        try {
            TimeWheel wheel = timeWheels.require(id);
            staleIds.forEach(wheel::remove);
            List<Message> snapshot = List.copyOf(Objects.requireNonNull(
                    storage.loadPendingByTimeWheel(id), "storage returned a null pending list"));
            for (Message message : snapshot) {
                wheel.add(message);
            }

            int rebuilt = snapshot.size();
            synchronized (state) {
                TimeWheelAssignment current = requireSlaveAssignment(id, assignmentVersion);
                resetForNewAssignment(state, current.assignmentVersion());
                state.lastAppliedSequence = startSequence;
                state.snapshotSequenceFloor = startSequence;
                state.appliedOperations.clear();
                state.indexedMessageIds.clear();
                snapshot.forEach(message -> state.indexedMessageIds.put(message.messageId(), Boolean.TRUE));

                List<BufferedOperation> buffered = new ArrayList<>(state.bufferedByOperation.values());
                buffered.sort(Comparator.comparingLong(item -> item.operation.sequence()));
                state.bufferedByOperation.clear();
                for (BufferedOperation item : buffered) {
                    try {
                        if (item.operation.sequence() <= startSequence) {
                            item.acknowledgement.complete(ack(id, state));
                        } else {
                            item.acknowledgement.complete(applyNow(state, item.operation));
                        }
                    } catch (RuntimeException exception) {
                        item.acknowledgement.completeExceptionally(exception);
                        throw exception;
                    }
                }
                if (!assignments.markSyncState(id, assignmentVersion, "in_sync")) {
                    throw new IllegalStateException("assignment changed while completing rebuild");
                }
                state.rebuilding = false;
                state.outOfSync = false;
                return new RebuildResult(
                        id, assignmentVersion, state.lastAppliedSequence, rebuilt);
            }
        } catch (RuntimeException exception) {
            failRebuild(id, assignmentVersion, state, exception);
            throw exception;
        }
    }

    public long lastAppliedSequence(String twId) {
        WheelState state = states.get(requireText(twId, "twId"));
        if (state == null) {
            return 0;
        }
        synchronized (state) {
            return state.lastAppliedSequence;
        }
    }

    public boolean isOutOfSync(String twId) {
        WheelState state = states.get(requireText(twId, "twId"));
        if (state == null) {
            return false;
        }
        synchronized (state) {
            return state.outOfSync;
        }
    }

    private SyncAck applyNow(WheelState state, ReplicaOperation operation) {
        Long alreadyAppliedAt = state.appliedOperations.get(operation.operationId());
        if (alreadyAppliedAt != null) {
            return ack(operation.twId(), state);
        }
        if (operation.sequence() <= state.snapshotSequenceFloor) {
            state.appliedOperations.put(operation.operationId(), operation.sequence());
            trimHistory(state.appliedOperations);
            return ack(operation.twId(), state);
        }
        if (operation.sequence() != state.lastAppliedSequence + 1) {
            throw new SequenceGapException(
                    "expected replica sequence " + (state.lastAppliedSequence + 1)
                            + " but received " + operation.sequence());
        }

        TimeWheel wheel = timeWheels.require(operation.twId());
        if (operation.type() == OperationType.ADD) {
            wheel.add(scheduleReference(operation));
            state.indexedMessageIds.put(operation.messageId(), Boolean.TRUE);
        } else {
            wheel.remove(operation.messageId());
            state.indexedMessageIds.remove(operation.messageId());
        }
        state.lastAppliedSequence = operation.sequence();
        state.appliedOperations.put(operation.operationId(), operation.sequence());
        trimHistory(state.appliedOperations);
        return ack(operation.twId(), state);
    }

    private void failRebuild(
            String twId, long assignmentVersion, WheelState state, RuntimeException failure) {
        synchronized (state) {
            state.rebuilding = false;
            state.outOfSync = true;
            state.bufferedByOperation.values().forEach(
                    item -> item.acknowledgement.completeExceptionally(failure));
            state.bufferedByOperation.clear();
        }
        assignments.markSyncState(twId, assignmentVersion, "out_of_sync");
    }

    private TimeWheelAssignment requireSlaveAssignment(String twId, long assignmentVersion) {
        TimeWheelAssignment assignment = assignments.current(twId)
                .orElseThrow(() -> new IllegalStateException("time wheel assignment is unavailable"));
        if (!assignment.isSlave(localNodeId)
                || assignment.assignmentVersion() != assignmentVersion) {
            throw new IllegalStateException("stale or misrouted replica operation");
        }
        return assignment;
    }

    private void resetForNewAssignment(WheelState state, long assignmentVersion) {
        if (state.assignmentVersion == assignmentVersion) {
            return;
        }
        state.assignmentVersion = assignmentVersion;
        state.lastAppliedSequence = 0;
        state.snapshotSequenceFloor = 0;
        state.outOfSync = false;
        state.rebuilding = false;
        state.appliedOperations.clear();
        state.bufferedByOperation.values().forEach(item -> item.acknowledgement.completeExceptionally(
                new IllegalStateException("replica assignment changed")));
        state.bufferedByOperation.clear();
        state.indexedMessageIds.clear();
    }

    private void trimHistory(LinkedHashMap<String, Long> history) {
        while (history.size() > bufferCapacity) {
            Iterator<String> iterator = history.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    private static SyncAck ack(String twId, WheelState state) {
        return new SyncAck(twId, state.assignmentVersion, state.lastAppliedSequence);
    }

    private static Message scheduleReference(ReplicaOperation operation) {
        return new Message(
                operation.messageId(),
                0,
                operation.deliverAt(),
                operation.twId(),
                null,
                null,
                null,
                null,
                MessageStatus.PENDING,
                0,
                operation.deliverAt(),
                0,
                null,
                null);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    @Override
    public void close() {
        rebuildExecutor.shutdownNow();
    }

    private static final class WheelState {
        private long assignmentVersion = -1;
        private long lastAppliedSequence;
        private long snapshotSequenceFloor;
        private boolean rebuilding;
        private boolean outOfSync;
        private final LinkedHashMap<String, Long> appliedOperations = new LinkedHashMap<>();
        private final LinkedHashMap<String, BufferedOperation> bufferedByOperation =
                new LinkedHashMap<>();
        private final LinkedHashMap<String, Boolean> indexedMessageIds = new LinkedHashMap<>();
    }

    private static final class BufferedOperation {
        private final ReplicaOperation operation;
        private final CompletableFuture<SyncAck> acknowledgement = new CompletableFuture<>();

        private BufferedOperation(ReplicaOperation operation) {
            this.operation = operation;
        }
    }

    private static final class SequenceGapException extends IllegalStateException {
        private SequenceGapException(String message) {
            super(message);
        }
    }

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
