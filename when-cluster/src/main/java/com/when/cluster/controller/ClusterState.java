package com.when.cluster.controller;

import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable input to Controller decisions and the pure rebalance planner. */
public record ClusterState(
        long revision,
        long observedAt,
        Map<String, NodeState> nodes,
        Map<String, AssignmentRecord> assignments) {
    public ClusterState {
        if (revision < 0 || observedAt < 0) {
            throw new IllegalArgumentException("revision and observedAt must not be negative");
        }
        nodes = immutable(nodes, "nodes");
        assignments = immutable(assignments, "assignments");
        nodes.forEach((key, value) -> {
            if (!key.equals(value.nodeId())) {
                throw new IllegalArgumentException("node map key must match nodeId");
            }
        });
        assignments.forEach((key, value) -> {
            if (!key.equals(value.twId())) {
                throw new IllegalArgumentException("assignment map key must match twId");
            }
        });
    }

    public ClusterState withObservedAt(long now) {
        return new ClusterState(revision, now, nodes, assignments);
    }

    private static <K, V> Map<K, V> immutable(Map<K, V> values, String field) {
        if (values == null) {
            throw new NullPointerException(field);
        }
        return Map.copyOf(new LinkedHashMap<>(values));
    }
}
