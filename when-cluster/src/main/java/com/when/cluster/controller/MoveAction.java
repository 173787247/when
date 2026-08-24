package com.when.cluster.controller;

/** A deterministic, side-effect-free movement proposal. */
public record MoveAction(
        String twId,
        String fromNode,
        String toNode,
        ReplicaRole role,
        long expectedAssignmentVersion) {
    public MoveAction {
        twId = Text.segment(twId, "twId");
        fromNode = Text.segment(fromNode, "fromNode");
        toNode = Text.segment(toNode, "toNode");
        if (fromNode.equals(toNode)) {
            throw new IllegalArgumentException("movement source and target must differ");
        }
        if (role == null) {
            throw new NullPointerException("role");
        }
        if (expectedAssignmentVersion < 0) {
            throw new IllegalArgumentException("expectedAssignmentVersion must not be negative");
        }
    }
}
