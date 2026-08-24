package com.when.cluster.controller;

import com.when.core.FailoverExecutor;
import com.when.core.TimeWheelAssignment;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Lesson-47 adapter: promotion/recovery uses Redis-first failover; other roles react via Watch. */
public final class FailoverActionExecutor implements ControllerActionExecutor {
    private final FailoverExecutor failoverExecutor;

    public FailoverActionExecutor(FailoverExecutor failoverExecutor) {
        this.failoverExecutor = Objects.requireNonNull(failoverExecutor, "failoverExecutor");
    }

    @Override
    public CompletionStage<Void> execute(
            AssignmentDecision decision,
            AssignmentRecord committedAssignment) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(committedAssignment, "committedAssignment");
        if (decision.action() != AssignmentAction.PROMOTE_SLAVE
                && decision.action() != AssignmentAction.RECOVER_MASTER) {
            if (decision.action() == AssignmentAction.ASSIGN_CANDIDATE) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "candidate rebuild requires a node-aware replica executor"));
            }
            return CompletableFuture.completedFuture(null);
        }
        var metadata = committedAssignment.metadata();
        TimeWheelAssignment assignment = new TimeWheelAssignment(
                committedAssignment.twId(),
                metadata.master(),
                metadata.slave(),
                metadata.status(),
                metadata.syncState(),
                metadata.assignmentVersion());
        return failoverExecutor.applyAssignment(assignment).thenApply(ignored -> null);
    }
}
