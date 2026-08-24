package com.when.core;

/** Result of rebuilding a Slave from Redis and replaying concurrent incremental operations. */
public record RebuildResult(
        String twId,
        long assignmentVersion,
        long lastAppliedSequence,
        int rebuiltMessages) {

    public RebuildResult {
        if (twId == null || twId.isBlank()) {
            throw new IllegalArgumentException("twId must not be blank");
        }
        if (assignmentVersion < 0 || lastAppliedSequence < 0 || rebuiltMessages < 0) {
            throw new IllegalArgumentException("rebuild counters must not be negative");
        }
    }
}
