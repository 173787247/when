package com.when.cluster.replica;

import com.when.core.DueMessageHandler;
import com.when.core.TimeWheelAssignment;
import java.util.Objects;

/** Prevents a demoted or partitioned Master from handing due messages to delivery. */
public final class FencedDueMessageHandler implements DueMessageHandler {
    private final String localNodeId;
    private final String twId;
    private final long assignmentVersion;
    private final ReplicaAssignmentStore assignments;
    private final DueMessageHandler delegate;

    public FencedDueMessageHandler(
            String localNodeId,
            String twId,
            long assignmentVersion,
            ReplicaAssignmentStore assignments,
            DueMessageHandler delegate) {
        this.localNodeId = requireText(localNodeId, "localNodeId");
        this.twId = requireText(twId, "twId");
        if (assignmentVersion < 0) {
            throw new IllegalArgumentException("assignmentVersion must not be negative");
        }
        this.assignmentVersion = assignmentVersion;
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void onDue(String messageId) {
        TimeWheelAssignment current = assignments.current(twId)
                .orElseThrow(() -> new IllegalStateException("time wheel assignment is unavailable"));
        if (!current.isMaster(localNodeId)
                || current.assignmentVersion() != assignmentVersion) {
            throw new IllegalStateException("stale Master is fenced from delivery");
        }
        delegate.onDue(messageId);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
