package com.when.cluster.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.when.cluster.etcd.TimeWheelMetadata;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultRebalancePlannerTest {
    private final DefaultRebalancePlanner planner = new DefaultRebalancePlanner();
    private final RebalancePolicy policy = new RebalancePolicy(
            Duration.ZERO, Duration.ofSeconds(60), 2);

    @Test
    void deterministicallyMovesOnlySlaveAndStopsAtOneReplicaSpread() {
        ClusterState state = state(120_000, Map.of(
                "tw-b", assignment("tw-b", "node-1", "node-2", 0),
                "tw-a", assignment("tw-a", "node-1", "node-2", 0)));

        List<MoveAction> first = planner.plan(state, policy);
        List<MoveAction> second = planner.plan(state, policy);

        assertEquals(first, second);
        assertEquals(List.of(new MoveAction(
                "tw-a", "node-2", "node-3", ReplicaRole.SLAVE, 3)), first);
    }

    @Test
    void honorsStablePeriodCooldownAndParallelMigrationLimit() {
        Map<String, NodeState> nodes = new LinkedHashMap<>();
        nodes.put("node-1", new NodeState("node-1", true, false, 0));
        nodes.put("node-2", new NodeState("node-2", true, false, 0));
        nodes.put("node-3", new NodeState("node-3", true, false, 119_999));
        Map<String, AssignmentRecord> assignments = Map.of(
                "tw-a", assignment("tw-a", "node-1", "node-2", 90_000),
                "tw-b", candidate("tw-b", "node-1", "node-2", "node-3"));
        ClusterState state = new ClusterState(20, 120_000, nodes, assignments);

        assertTrue(planner.plan(state, policy).isEmpty());
    }

    private static ClusterState state(
            long now,
            Map<String, AssignmentRecord> assignments) {
        Map<String, NodeState> nodes = new LinkedHashMap<>();
        nodes.put("node-3", new NodeState("node-3", true, false, 0));
        nodes.put("node-1", new NodeState("node-1", true, false, 0));
        nodes.put("node-2", new NodeState("node-2", true, false, 0));
        return new ClusterState(20, now, nodes, assignments);
    }

    private static AssignmentRecord assignment(
            String twId,
            String master,
            String slave,
            long lastMovedAt) {
        return new AssignmentRecord(
                twId,
                new TimeWheelMetadata(
                        master,
                        slave,
                        "running",
                        "in_sync",
                        3,
                        null,
                        null,
                        null,
                        lastMovedAt),
                10,
                lastMovedAt);
    }

    private static AssignmentRecord candidate(
            String twId,
            String master,
            String slave,
            String candidate) {
        return new AssignmentRecord(
                twId,
                new TimeWheelMetadata(
                        master,
                        slave,
                        "running",
                        "in_sync",
                        3,
                        "move",
                        candidate,
                        "rebuilding",
                        0),
                10);
    }
}
