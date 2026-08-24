package com.when.cluster.controller;

import com.when.cluster.etcd.TimeWheelMetadata;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic Slave-first planner with stable-period, cooldown and concurrency bounds. */
public final class DefaultRebalancePlanner implements RebalancePlanner {
    @Override
    public List<MoveAction> plan(ClusterState state, RebalancePolicy policy) {
        if (state == null) {
            throw new NullPointerException("state");
        }
        if (policy == null) {
            throw new NullPointerException("policy");
        }

        List<String> candidates = state.nodes().values().stream()
                .filter(node -> node.canReceiveReplica(state.observedAt(), policy))
                .map(NodeState::nodeId)
                .sorted()
                .toList();
        if (candidates.size() < 2) {
            return List.of();
        }

        int activeMoves = (int) state.assignments().values().stream()
                .filter(assignment -> assignment.metadata().candidateSlave() != null)
                .count();
        int remaining = policy.maxParallelMoves() - activeMoves;
        if (remaining <= 0) {
            return List.of();
        }

        Map<String, Integer> load = new HashMap<>();
        candidates.forEach(node -> load.put(node, 0));
        for (AssignmentRecord assignment : state.assignments().values()) {
            addIfCandidate(load, assignment.metadata().master());
            addIfCandidate(load, assignment.metadata().slave());
            addIfCandidate(load, assignment.metadata().candidateSlave());
        }

        List<AssignmentRecord> assignments = state.assignments().values().stream()
                .sorted(Comparator.comparing(AssignmentRecord::twId))
                .toList();
        List<MoveAction> result = new ArrayList<>();
        Set<String> plannedWheels = new HashSet<>();

        while (result.size() < remaining && spread(load) > 1) {
            boolean moved = false;
            List<String> sources = load.keySet().stream()
                    .sorted(Comparator
                            .<String>comparingInt(load::get)
                            .reversed()
                            .thenComparing(Comparator.naturalOrder()))
                    .toList();
            List<String> destinations = load.keySet().stream()
                    .sorted(Comparator.comparingInt((String node) -> load.get(node))
                            .thenComparing(Comparator.naturalOrder()))
                    .toList();

            for (String source : sources) {
                for (AssignmentRecord assignment : assignments) {
                    TimeWheelMetadata metadata = assignment.metadata();
                    if (!metadata.slave().equals(source)
                            || metadata.candidateSlave() != null
                            || plannedWheels.contains(assignment.twId())
                            || inCooldown(assignment, state.observedAt(), policy)) {
                        continue;
                    }
                    for (String destination : destinations) {
                        if (load.get(source) - load.get(destination) <= 1
                                || destination.equals(metadata.master())
                                || destination.equals(metadata.slave())) {
                            continue;
                        }
                        result.add(new MoveAction(
                                assignment.twId(),
                                source,
                                destination,
                                ReplicaRole.SLAVE,
                                metadata.assignmentVersion()));
                        plannedWheels.add(assignment.twId());
                        load.compute(source, (ignored, value) -> value - 1);
                        load.compute(destination, (ignored, value) -> value + 1);
                        moved = true;
                        break;
                    }
                    if (moved) {
                        break;
                    }
                }
                if (moved) {
                    break;
                }
            }
            if (!moved) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private static void addIfCandidate(Map<String, Integer> load, String nodeId) {
        if (nodeId != null && load.containsKey(nodeId)) {
            load.compute(nodeId, (ignored, value) -> value + 1);
        }
    }

    private static int spread(Map<String, Integer> load) {
        int minimum = load.values().stream().mapToInt(Integer::intValue).min().orElse(0);
        int maximum = load.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return maximum - minimum;
    }

    private static boolean inCooldown(
            AssignmentRecord assignment,
            long now,
            RebalancePolicy policy) {
        return assignment.lastMovedAt() > 0
                && now - assignment.lastMovedAt() < policy.timeWheelCooldown().toMillis();
    }
}
