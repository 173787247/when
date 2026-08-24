package com.when.core;

/** Observable result of applying a Controller-persisted assignment on one node. */
public record PromotionResult(
        String twId,
        long assignmentVersion,
        boolean promoted,
        int recoveredMessages) {

    public PromotionResult {
        if (twId == null || twId.isBlank()) {
            throw new IllegalArgumentException("twId must not be blank");
        }
        if (assignmentVersion < 0 || recoveredMessages < 0) {
            throw new IllegalArgumentException("promotion counters must not be negative");
        }
    }
}
