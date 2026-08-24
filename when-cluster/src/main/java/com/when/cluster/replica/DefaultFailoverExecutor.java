package com.when.cluster.replica;

import com.when.core.FailoverExecutor;
import com.when.core.PromotionResult;
import com.when.core.StoragePlugin;
import com.when.core.TimeWheel;
import com.when.core.TimeWheelAssignment;
import com.when.core.TimeWheelRegistry;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Redis-first promotion executor for assignments already committed by the Controller. */
public final class DefaultFailoverExecutor implements FailoverExecutor, AutoCloseable {
    private final String localNodeId;
    private final TimeWheelRegistry timeWheels;
    private final StoragePlugin storage;
    private final ReplicaAssignmentStore assignments;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    public DefaultFailoverExecutor(
            String localNodeId,
            TimeWheelRegistry timeWheels,
            StoragePlugin storage,
            ReplicaAssignmentStore assignments) {
        this.localNodeId = requireText(localNodeId, "localNodeId");
        this.timeWheels = Objects.requireNonNull(timeWheels, "timeWheels");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "when-failover-" + safe(localNodeId));
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public CompletionStage<PromotionResult> applyAssignment(TimeWheelAssignment assignment) {
        Objects.requireNonNull(assignment, "assignment");
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("failover executor is closed"));
        }
        if (!assignment.isMaster(localNodeId)) {
            return CompletableFuture.completedFuture(new PromotionResult(
                    assignment.twId(), assignment.assignmentVersion(), false, 0));
        }
        return CompletableFuture.supplyAsync(() -> promote(assignment), executor);
    }

    private PromotionResult promote(TimeWheelAssignment requested) {
        requireCurrent(requested);
        TimeWheel wheel = timeWheels.require(requested.twId());

        // Redis is authoritative. Duplicate add is intentionally safe and replaces any lagging
        // Slave handle for the same message id before the scheduler becomes active.
        int recovered = 0;
        for (var message : Objects.requireNonNull(
                storage.loadPendingByTimeWheel(requested.twId()),
                "storage returned a null pending list")) {
            if (wheel instanceof ReplicatedTimeWheel replicated) {
                replicated.restore(message);
            } else {
                wheel.add(message);
            }
            recovered++;
        }

        // Close the check/start race as far as the local executor can: a different assignment is
        // rejected immediately before scheduling starts, while due callbacks remain epoch-fenced.
        requireCurrent(requested);
        wheel.start();
        return new PromotionResult(
                requested.twId(), requested.assignmentVersion(), true, recovered);
    }

    private void requireCurrent(TimeWheelAssignment expected) {
        TimeWheelAssignment current = assignments.current(expected.twId())
                .orElseThrow(() -> new IllegalStateException("time wheel assignment is unavailable"));
        if (!current.isMaster(localNodeId)
                || current.assignmentVersion() != expected.assignmentVersion()
                || !current.master().equals(expected.master())
                || !current.slave().equals(expected.slave())) {
            throw new IllegalStateException("assignment changed during promotion");
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow();
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
