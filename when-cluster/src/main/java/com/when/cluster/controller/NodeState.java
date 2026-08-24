package com.when.cluster.controller;

import com.when.cluster.membership.NodeInfo;

/** Controller-relevant node state; credentials and message facts never enter this view. */
public record NodeState(
        String nodeId,
        boolean ready,
        boolean draining,
        long eligibleSince) {
    public NodeState {
        nodeId = Text.segment(nodeId, "nodeId");
        if (eligibleSince < 0) {
            throw new IllegalArgumentException("eligibleSince must not be negative");
        }
    }

    public static NodeState ready(NodeInfo node, long observedAt) {
        if (node == null) {
            throw new NullPointerException("node");
        }
        return new NodeState(node.nodeId(), true, false, observedAt);
    }

    public boolean canReceiveReplica(long now, RebalancePolicy policy) {
        return ready && !draining && now - eligibleSince >= policy.nodeStablePeriod().toMillis();
    }
}
