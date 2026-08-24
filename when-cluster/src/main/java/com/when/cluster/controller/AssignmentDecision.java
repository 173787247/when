package com.when.cluster.controller;

/** Fenced Controller decision described by lesson 48. */
public record AssignmentDecision(
        String decisionId,
        long controllerTerm,
        String twId,
        long expectedModRevision,
        long nextAssignmentVersion,
        AssignmentAction action,
        String fromNode,
        String toNode) {
    public AssignmentDecision {
        decisionId = Text.require(decisionId, "decisionId");
        if (controllerTerm <= 0) {
            throw new IllegalArgumentException("controllerTerm must be positive");
        }
        twId = Text.segment(twId, "twId");
        if (expectedModRevision < 0 || nextAssignmentVersion < 0) {
            throw new IllegalArgumentException("revisions and versions must not be negative");
        }
        if (action == null) {
            throw new NullPointerException("action");
        }
        fromNode = Text.optional(fromNode, "fromNode");
        toNode = Text.optional(toNode, "toNode");
        if (action == AssignmentAction.CREATE_TIME_WHEEL) {
            if (fromNode == null || toNode == null || fromNode.equals(toNode)) {
                throw new IllegalArgumentException("create requires different Master and Slave nodes");
            }
        } else if (toNode == null) {
            throw new IllegalArgumentException("assignment action requires a target node");
        }
    }
}
