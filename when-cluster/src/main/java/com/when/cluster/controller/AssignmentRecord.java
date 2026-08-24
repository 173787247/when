package com.when.cluster.controller;

import com.when.cluster.etcd.TimeWheelMetadata;

/** Time-wheel metadata plus etcd revision and local movement bookkeeping. */
public record AssignmentRecord(
        String twId,
        TimeWheelMetadata metadata,
        long modRevision,
        long lastMovedAt) {
    public AssignmentRecord {
        twId = Text.segment(twId, "twId");
        if (metadata == null) {
            throw new NullPointerException("metadata");
        }
        if (modRevision < 0 || lastMovedAt < 0) {
            throw new IllegalArgumentException("revisions and timestamps must not be negative");
        }
    }

    public AssignmentRecord(String twId, TimeWheelMetadata metadata, long modRevision) {
        this(twId, metadata, modRevision, 0);
    }
}
