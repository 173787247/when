package com.when.cluster.replica;

import com.when.core.Message;
import com.when.core.OperationType;
import com.when.core.ReplicaOperation;
import com.when.core.ReplicaSync;
import com.when.core.TimeWheel;
import com.when.core.TimeWheelAssignment;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Master-side time-wheel decorator that mirrors successful local mutations asynchronously. */
public final class ReplicatedTimeWheel implements TimeWheel {
    private final String localNodeId;
    private final TimeWheel delegate;
    private final ReplicaAssignmentStore assignments;
    private final ReplicaSync replicaSync;
    private final Supplier<String> operationIds;
    private final Object mutationLock = new Object();
    private long assignmentVersion = -1;
    private long sequence;

    public ReplicatedTimeWheel(
            String localNodeId,
            TimeWheel delegate,
            ReplicaAssignmentStore assignments,
            ReplicaSync replicaSync) {
        this(localNodeId, delegate, assignments, replicaSync, () -> UUID.randomUUID().toString());
    }

    public ReplicatedTimeWheel(
            String localNodeId,
            TimeWheel delegate,
            ReplicaAssignmentStore assignments,
            ReplicaSync replicaSync,
            Supplier<String> operationIds) {
        this.localNodeId = requireText(localNodeId, "localNodeId");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.replicaSync = Objects.requireNonNull(replicaSync, "replicaSync");
        this.operationIds = Objects.requireNonNull(operationIds, "operationIds");
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public void add(Message message) {
        Objects.requireNonNull(message, "message");
        synchronized (mutationLock) {
            TimeWheelAssignment assignment = requireMasterAssignment();
            resetSequenceIfAssignmentChanged(assignment.assignmentVersion());
            delegate.add(message);
            enqueue(new ReplicaOperation(
                    requireText(operationIds.get(), "operationId"),
                    id(),
                    assignment.assignmentVersion(),
                    ++sequence,
                    OperationType.ADD,
                    message.messageId(),
                    message.deliverAt()));
        }
    }

    @Override
    public void remove(String messageId) {
        synchronized (mutationLock) {
            TimeWheelAssignment assignment = requireMasterAssignment();
            resetSequenceIfAssignmentChanged(assignment.assignmentVersion());
            delegate.remove(messageId);
            enqueue(new ReplicaOperation(
                    requireText(operationIds.get(), "operationId"),
                    id(),
                    assignment.assignmentVersion(),
                    ++sequence,
                    OperationType.REMOVE,
                    messageId,
                    0));
        }
    }

    @Override
    public void start() {
        synchronized (mutationLock) {
            TimeWheelAssignment assignment = requireMasterAssignment();
            resetSequenceIfAssignmentChanged(assignment.assignmentVersion());
            delegate.start();
        }
    }

    @Override
    public void stop() {
        delegate.stop();
    }

    public long currentSequence() {
        synchronized (mutationLock) {
            return sequence;
        }
    }

    public long assignmentVersion() {
        synchronized (mutationLock) {
            return assignmentVersion;
        }
    }

    /** Adds a Redis-restored reference without emitting a new replication operation. */
    void restore(Message message) {
        delegate.add(Objects.requireNonNull(message, "message"));
    }

    private void enqueue(ReplicaOperation operation) {
        try {
            replicaSync.sync(operation);
        } catch (RuntimeException exception) {
            // Redis already owns the fact and the local index mutation succeeded. The sync
            // implementation marks the replica out-of-sync; the Master write path remains live.
            replicaSync.markOutOfSync(
                    operation.twId(), operation.assignmentVersion(), exception.getClass().getSimpleName());
        }
    }

    private TimeWheelAssignment requireMasterAssignment() {
        TimeWheelAssignment assignment = assignments.current(id())
                .orElseThrow(() -> new IllegalStateException("time wheel assignment is unavailable"));
        if (!assignment.isMaster(localNodeId)) {
            throw new IllegalStateException("local node is not the assigned Master");
        }
        return assignment;
    }

    private void resetSequenceIfAssignmentChanged(long currentAssignmentVersion) {
        if (assignmentVersion != currentAssignmentVersion) {
            assignmentVersion = currentAssignmentVersion;
            sequence = 0;
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
