package com.when.cluster.replica;

import com.when.core.OperationType;
import com.when.core.RebuildResult;
import com.when.core.ReplicaOperation;
import com.when.core.TimeWheelAssignment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReplicaOperationApplierTest {
    private ReplicaTestSupport.AssignmentStore assignments;
    private ReplicaTestSupport.RecordingWheel wheel;
    private ReplicaTestSupport.Storage storage;
    private ReplicaOperationApplier applier;

    @BeforeEach
    void setUp() {
        assignments = new ReplicaTestSupport.AssignmentStore();
        assignments.put(new TimeWheelAssignment(
                "tw-a", "master", "slave", "running", "in_sync", 7));
        wheel = new ReplicaTestSupport.RecordingWheel("tw-a");
        storage = new ReplicaTestSupport.Storage();
        applier = new ReplicaOperationApplier(
                "slave", new ReplicaTestSupport.Registry(wheel), storage, assignments, 8);
    }

    @Test
    void appliesInOrderAndIgnoresDuplicateOperationId() {
        ReplicaOperation add = operation("op-1", 1, OperationType.ADD, "message-1", 100);

        assertEquals(1, applier.apply(add).toCompletableFuture().join().lastAppliedSequence());
        assertEquals(1, applier.apply(add).toCompletableFuture().join().lastAppliedSequence());
        assertTrue(wheel.contains("message-1"));
        assertEquals(2, applier.apply(operation(
                        "op-2", 2, OperationType.REMOVE, "message-1", 0))
                .toCompletableFuture().join().lastAppliedSequence());
        assertFalse(wheel.contains("message-1"));
    }

    @Test
    void sequenceGapMarksReplicaOutOfSync() {
        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> applier.apply(operation("op-2", 2, OperationType.ADD, "message-2", 200))
                        .toCompletableFuture().join());

        assertTrue(failure.getCause().getMessage().contains("expected replica sequence"));
        assertTrue(applier.isOutOfSync("tw-a"));
        assertEquals("out_of_sync", assignments.current("tw-a").orElseThrow().syncState());
        assertEquals(0, wheel.size());
    }

    @Test
    void rebuildReplacesTrackedStateFromRedisAndReturnsInSync() {
        applier.apply(operation("op-1", 1, OperationType.ADD, "stale", 100))
                .toCompletableFuture().join();
        storage.pending = List.of(ReplicaTestSupport.message("durable", "tw-a", 500));
        assignments.markSyncState("tw-a", 7, "out_of_sync");

        RebuildResult result = applier.rebuild("tw-a", 7, 4).toCompletableFuture().join();

        assertFalse(wheel.contains("stale"));
        assertTrue(wheel.contains("durable"));
        assertEquals(4, result.lastAppliedSequence());
        assertEquals(1, result.rebuiltMessages());
        assertEquals("in_sync", assignments.current("tw-a").orElseThrow().syncState());
    }

    @Test
    void rebuildBuffersAndCatchesUpConcurrentIncrementalWrite() throws Exception {
        CountDownLatch snapshotStarted = new CountDownLatch(1);
        CountDownLatch releaseSnapshot = new CountDownLatch(1);
        storage = new ReplicaTestSupport.Storage() {
            @Override
            public List<com.when.core.Message> loadPendingByTimeWheel(String timeWheelId) {
                snapshotStarted.countDown();
                try {
                    if (!releaseSnapshot.await(1, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("snapshot was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return List.of(ReplicaTestSupport.message("snapshot", "tw-a", 500));
            }
        };
        applier = new ReplicaOperationApplier(
                "slave", new ReplicaTestSupport.Registry(wheel), storage, assignments, 8);
        assignments.markSyncState("tw-a", 7, "out_of_sync");

        CompletableFuture<RebuildResult> rebuilding = CompletableFuture.supplyAsync(() ->
                applier.rebuild("tw-a", 7, 4).toCompletableFuture().join());
        assertTrue(snapshotStarted.await(1, TimeUnit.SECONDS));
        var liveAck = applier.apply(operation("op-5", 5, OperationType.ADD, "live", 600));
        assertFalse(liveAck.toCompletableFuture().isDone());
        releaseSnapshot.countDown();

        assertEquals(5, rebuilding.get(1, TimeUnit.SECONDS).lastAppliedSequence());
        assertEquals(5, liveAck.toCompletableFuture().get(1, TimeUnit.SECONDS)
                .lastAppliedSequence());
        assertTrue(wheel.contains("snapshot"));
        assertTrue(wheel.contains("live"));
    }

    @Test
    void rejectsStaleAssignmentVersionWithoutTouchingCurrentMetadata() {
        assertThrows(
                IllegalStateException.class,
                () -> applier.apply(new ReplicaOperation(
                        "old", "tw-a", 6, 1, OperationType.ADD, "message", 100)));
        assertEquals("in_sync", assignments.current("tw-a").orElseThrow().syncState());
    }

    private static ReplicaOperation operation(
            String operationId,
            long sequence,
            OperationType type,
            String messageId,
            long deliverAt) {
        return new ReplicaOperation(
                operationId, "tw-a", 7, sequence, type, messageId, deliverAt);
    }
}
