package com.when.cluster.controller;

import java.util.Optional;

public record CommitResult(
        boolean committed,
        long revision,
        Optional<AssignmentRecord> assignment) {
    public CommitResult {
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        assignment = assignment == null ? Optional.empty() : assignment;
        if (committed && assignment.isEmpty()) {
            throw new IllegalArgumentException("committed result requires an assignment");
        }
    }

    public static CommitResult conflict(long revision) {
        return new CommitResult(false, revision, Optional.empty());
    }
}
