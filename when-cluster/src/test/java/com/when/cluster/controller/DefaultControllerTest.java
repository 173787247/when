package com.when.cluster.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.when.cluster.etcd.TimeWheelMetadata;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class DefaultControllerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(120_000), ZoneOffset.UTC);
    private static final RebalancePolicy POLICY = new RebalancePolicy(
            Duration.ZERO, Duration.ZERO, 2);

    @Test
    void failedMasterPromotesInSyncSlaveThenKeepsOldSlaveUntilCandidateRebuilds() throws Exception {
        FakeStore store = new FakeStore(nodes("node-2", "node-3"), Map.of(
                "tw-a", assignment("tw-a", "node-1", "node-2", "in_sync")));
        store.conflictsRemaining = 1;
        List<AssignmentAction> executed = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (DefaultController controller = controller(store, executed)) {
            controller.onControllerElected(new ControllerTerm("node-9", 50));
            controller.onClusterEvent(ClusterEvent.nodeLeft("delete-node-1", 51, "node-1"));

            await(() -> executed.size() >= 2);
            assertEquals(AssignmentAction.PROMOTE_SLAVE, executed.get(0));
            assertEquals(AssignmentAction.ASSIGN_CANDIDATE, executed.get(1));
            TimeWheelMetadata result = store.records.get("tw-a").metadata();
            assertEquals("node-2", result.master());
            assertEquals("node-1", result.slave());
            assertEquals("node-3", result.candidateSlave());
            assertEquals("rebuilding", result.candidateState());
            assertTrue(store.casAttempts >= 3, "the stale CAS must be recalculated");
        }
    }

    @Test
    void createIsDeterministicIdempotentAndAlwaysUsesDifferentNodes() {
        FakeStore store = new FakeStore(nodes("node-3", "node-1", "node-2"), Map.of());
        List<AssignmentAction> executed = new ArrayList<>();
        try (DefaultController controller = controller(store, executed)) {
            controller.onControllerElected(new ControllerTerm("node-9", 50));

            TimeWheelAssignmentView created = controller.createTimeWheel(new TimeWheelSpec("tw-new"));
            TimeWheelAssignmentView repeated = controller.createTimeWheel(new TimeWheelSpec("tw-new"));

            assertEquals(created, repeated);
            assertEquals("node-1", created.master());
            assertEquals("node-2", created.slave());
            assertNotEquals(created.master(), created.slave());
            assertEquals(List.of(AssignmentAction.CREATE_TIME_WHEEL), executed);
        }
    }

    @Test
    void outOfSyncReplicaIsRebuiltInsteadOfPromoted() throws Exception {
        FakeStore store = new FakeStore(nodes("node-1", "node-2", "node-3"), Map.of(
                "tw-a", assignment("tw-a", "node-1", "node-2", "out_of_sync")));
        List<AssignmentAction> executed = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (DefaultController controller = controller(store, executed)) {
            controller.onControllerElected(new ControllerTerm("node-9", 50));
            controller.onClusterEvent(ClusterEvent.outOfSync("sync-tw-a", 51, "tw-a"));

            await(() -> !executed.isEmpty());
            assertEquals(List.of(AssignmentAction.REBUILD_SLAVE), executed);
            assertEquals("rebuilding", store.records.get("tw-a").metadata().syncState());
        }
    }

    @Test
    void successorReloadsCommittedStateAndDoesNotReplayFailover() throws Exception {
        FakeStore store = new FakeStore(nodes("node-2", "node-3"), Map.of(
                "tw-a", assignment("tw-a", "node-1", "node-2", "in_sync")));
        List<AssignmentAction> firstTerm = new java.util.concurrent.CopyOnWriteArrayList<>();
        ClusterEvent deletion = ClusterEvent.nodeLeft("delete-node-1", 51, "node-1");
        try (DefaultController controller = controller(store, firstTerm)) {
            controller.onControllerElected(new ControllerTerm("node-9", 50));
            controller.onClusterEvent(deletion);
            await(() -> firstTerm.size() >= 2);
        }

        List<AssignmentAction> successor = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (DefaultController controller = controller(store, successor)) {
            controller.onControllerElected(new ControllerTerm("node-8", 60));
            controller.onClusterEvent(deletion);
            Thread.sleep(100);
            assertTrue(successor.isEmpty());
        }
    }

    @Test
    void compactedWatchReloadsSnapshotAndInstallsAWatchFromTheNewRevision() throws Exception {
        FakeStore store = new FakeStore(nodes("node-1", "node-2"), Map.of());
        try (DefaultController controller = controller(store, new ArrayList<>())) {
            controller.onControllerElected(new ControllerTerm("node-9", 50));
            assertEquals(1, store.watchCount);

            store.snapshotRequired.run();
            await(() -> store.watchCount == 2);
            assertTrue(store.lastWatchRevision > store.revision);
        }
    }

    @Test
    void oldSlaveIsReplacedOnlyAfterCandidateRebuildCompletes() throws Exception {
        FakeStore store = new FakeStore(nodes("node-2", "node-3"), Map.of(
                "tw-a", assignment("tw-a", "node-1", "node-2", "in_sync")));
        CompletableFuture<Void> candidateRebuild = new CompletableFuture<>();
        List<AssignmentAction> executed = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (DefaultController controller = new DefaultController(
                store,
                new DefaultRebalancePlanner(),
                POLICY,
                (decision, assignment) -> {
                    executed.add(decision.action());
                    return decision.action() == AssignmentAction.ASSIGN_CANDIDATE
                            ? candidateRebuild
                            : CompletableFuture.completedFuture(null);
                },
                CLOCK,
                Duration.ZERO,
                Duration.ofHours(1))) {
            controller.onControllerElected(new ControllerTerm("node-9", 50));
            controller.onClusterEvent(ClusterEvent.nodeLeft("delete-node-1", 51, "node-1"));
            await(() -> store.records.get("tw-a").metadata().candidateSlave() != null);
            assertEquals("node-1", store.records.get("tw-a").metadata().slave());

            candidateRebuild.complete(null);
            await(() -> "node-3".equals(store.records.get("tw-a").metadata().slave()));
            assertEquals(null, store.records.get("tw-a").metadata().candidateSlave());
            assertEquals(List.of(
                    AssignmentAction.PROMOTE_SLAVE,
                    AssignmentAction.ASSIGN_CANDIDATE,
                    AssignmentAction.MARK_CANDIDATE_IN_SYNC,
                    AssignmentAction.COMPLETE_SLAVE_MOVE), executed);
        }
    }

    @Test
    void failedSlaveGetsARebuildingCandidateWithoutMovingMaster() throws Exception {
        FakeStore store = new FakeStore(nodes("node-1", "node-3"), Map.of(
                "tw-a", assignment("tw-a", "node-1", "node-2", "in_sync")));
        List<AssignmentAction> executed = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (DefaultController controller = controller(store, executed)) {
            controller.onControllerElected(new ControllerTerm("node-9", 50));
            controller.onClusterEvent(ClusterEvent.nodeLeft("delete-node-2", 51, "node-2"));

            await(() -> !executed.isEmpty());
            assertEquals(List.of(AssignmentAction.ASSIGN_CANDIDATE), executed);
            TimeWheelMetadata metadata = store.records.get("tw-a").metadata();
            assertEquals("node-1", metadata.master());
            assertEquals("node-2", metadata.slave());
            assertEquals("node-3", metadata.candidateSlave());
        }
    }

    @Test
    void failedCandidateIsAbortedWhileOldSlaveRemainsAvailableForLaterPlanning() throws Exception {
        AssignmentRecord moving = new AssignmentRecord(
                "tw-a",
                new TimeWheelMetadata(
                        "node-1", "node-2", "running", "in_sync", 5,
                        "move", "node-3", "rebuilding", 100_000),
                10,
                100_000);
        FakeStore store = new FakeStore(nodes("node-1", "node-2", "node-4"), Map.of("tw-a", moving));
        List<AssignmentAction> executed = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (DefaultController controller = controller(store, executed)) {
            controller.onControllerElected(new ControllerTerm("node-9", 50));
            controller.onClusterEvent(ClusterEvent.nodeLeft("delete-node-3", 51, "node-3"));

            await(() -> !executed.isEmpty());
            assertEquals(List.of(AssignmentAction.ABORT_CANDIDATE), executed);
            TimeWheelMetadata metadata = store.records.get("tw-a").metadata();
            assertEquals("node-2", metadata.slave());
            assertEquals(null, metadata.candidateSlave());
        }
    }

    private static DefaultController controller(
            FakeStore store,
            List<AssignmentAction> executed) {
        return new DefaultController(
                store,
                new DefaultRebalancePlanner(),
                POLICY,
                (decision, assignment) -> {
                    executed.add(decision.action());
                    if (decision.action() == AssignmentAction.ASSIGN_CANDIDATE) {
                        return new CompletableFuture<>();
                    }
                    return CompletableFuture.completedFuture(null);
                },
                CLOCK,
                Duration.ZERO,
                Duration.ofHours(1));
    }

    private static Map<String, NodeState> nodes(String... nodeIds) {
        Map<String, NodeState> result = new LinkedHashMap<>();
        for (String nodeId : nodeIds) {
            result.put(nodeId, new NodeState(nodeId, true, false, 0));
        }
        return result;
    }

    private static AssignmentRecord assignment(
            String twId,
            String master,
            String slave,
            String syncState) {
        return new AssignmentRecord(
                twId,
                new TimeWheelMetadata(master, slave, "running", syncState, 4),
                10);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "condition was not met before timeout");
    }

    private static final class FakeStore implements AssignmentStore {
        private final Map<String, NodeState> nodes;
        private final Map<String, AssignmentRecord> records;
        private long revision = 40;
        private int conflictsRemaining;
        private int casAttempts;
        private int watchCount;
        private long lastWatchRevision;
        private Runnable snapshotRequired;

        private FakeStore(
                Map<String, NodeState> nodes,
                Map<String, AssignmentRecord> assignments) {
            this.nodes = new LinkedHashMap<>(nodes);
            this.records = new LinkedHashMap<>(assignments);
        }

        @Override
        public synchronized ClusterState loadSnapshot(long observedAt) {
            return new ClusterState(revision, observedAt, nodes, records);
        }

        @Override
        public synchronized CommitResult compareAndSet(AssignmentDecision decision) {
            casAttempts++;
            AssignmentRecord current = records.get(decision.twId());
            if (conflictsRemaining-- > 0) {
                return new CommitResult(false, revision, Optional.ofNullable(current));
            }
            if ((current == null ? 0 : current.modRevision()) != decision.expectedModRevision()) {
                return new CommitResult(false, revision, Optional.ofNullable(current));
            }
            TimeWheelMetadata metadata = apply(decision, current == null ? null : current.metadata());
            revision++;
            AssignmentRecord committed = new AssignmentRecord(
                    decision.twId(), metadata, revision, metadata.lastMovedAt());
            records.put(decision.twId(), committed);
            return new CommitResult(true, revision, Optional.of(committed));
        }

        @Override
        public WatchHandle watchFrom(
                long revision,
                Consumer<ClusterEvent> eventHandler,
                Runnable snapshotRequired) {
            watchCount++;
            lastWatchRevision = revision;
            this.snapshotRequired = snapshotRequired;
            return () -> { };
        }

        private static TimeWheelMetadata apply(
                AssignmentDecision decision,
                TimeWheelMetadata current) {
            long version = decision.nextAssignmentVersion();
            return switch (decision.action()) {
                case CREATE_TIME_WHEEL -> new TimeWheelMetadata(
                        decision.fromNode(), decision.toNode(), "running", "in_sync", 1);
                case PROMOTE_SLAVE -> new TimeWheelMetadata(
                        current.slave(),
                        current.master(),
                        "recovering",
                        "out_of_sync",
                        version,
                        decision.decisionId(),
                        null,
                        null,
                        120_000);
                case RECOVER_MASTER -> new TimeWheelMetadata(
                        decision.toNode(),
                        current.slave().equals(decision.toNode())
                                ? current.master()
                                : current.slave(),
                        "recovering",
                        "out_of_sync",
                        version,
                        decision.decisionId(),
                        null,
                        null,
                        120_000);
                case ASSIGN_CANDIDATE -> new TimeWheelMetadata(
                        current.master(),
                        current.slave(),
                        current.status(),
                        current.syncState(),
                        version,
                        decision.decisionId(),
                        decision.toNode(),
                        "rebuilding",
                        120_000);
                case ABORT_CANDIDATE -> new TimeWheelMetadata(
                        current.master(),
                        current.slave(),
                        current.status(),
                        current.syncState(),
                        version,
                        decision.decisionId(),
                        null,
                        null,
                        current.lastMovedAt());
                case REBUILD_SLAVE -> new TimeWheelMetadata(
                        current.master(),
                        current.slave(),
                        current.status(),
                        "rebuilding",
                        version,
                        decision.decisionId(),
                        current.candidateSlave(),
                        current.candidateState(),
                        current.lastMovedAt());
                case MARK_CANDIDATE_IN_SYNC -> new TimeWheelMetadata(
                        current.master(),
                        current.slave(),
                        current.status(),
                        current.syncState(),
                        version,
                        decision.decisionId(),
                        current.candidateSlave(),
                        "in_sync",
                        current.lastMovedAt());
                case COMPLETE_SLAVE_MOVE -> new TimeWheelMetadata(
                        current.master(),
                        current.candidateSlave(),
                        "running",
                        "in_sync",
                        version,
                        decision.decisionId(),
                        null,
                        null,
                        120_000);
            };
        }
    }
}
