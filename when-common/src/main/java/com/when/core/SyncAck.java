package com.when.core;

/** Highest sequence applied to the Slave's rebuildable in-memory index. */
public record SyncAck(String twId, long assignmentVersion, long lastAppliedSequence) {
    public SyncAck {
        if (twId == null || twId.isBlank()) {
            throw new IllegalArgumentException("twId must not be blank");
        }
        if (assignmentVersion < 0 || lastAppliedSequence < 0) {
            throw new IllegalArgumentException("versions and sequences must not be negative");
        }
    }
}
