package com.when.cluster.replica;

import com.when.core.OperationType;
import com.when.core.RebuildResult;
import com.when.core.ReplicaOperation;
import com.when.core.SyncAck;
import com.when.core.TimeWheelAssignment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class DefaultReplicaSyncTest {
    @Test
    void networkWaitRunsOffTheMasterCallerAndAcknowledgesInOrder() throws Exception {
        ReplicaTestSupport.AssignmentStore assignments = assignments();
        ReplicaTestSupport.RecordingWheel slaveWheel = new ReplicaTestSupport.RecordingWheel("tw-a");
        ReplicaOperationApplier applier = applier(assignments, slaveWheel);
        CountDownLatch release = new CountDownLatch(1);
        ReplicaTransport transport = operation -> CompletableFuture.supplyAsync(() -> {
            await(release);
            return applier.apply(operation).toCompletableFuture().join();
        });

        try (DefaultReplicaSync sync = new DefaultReplicaSync(
                "master", assignments, transport, applier, 4, 3, Duration.ofSeconds(2))) {
            long startedAt = System.nanoTime();
            var acknowledgement = sync.sync(operation("op-1", 1));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertTrue(elapsedMillis < 100, "sync offer unexpectedly waited for transport");
            assertFalse(acknowledgement.toCompletableFuture().isDone());
            release.countDown();
            assertEquals(1, acknowledgement.toCompletableFuture().get(1, TimeUnit.SECONDS)
                    .lastAppliedSequence());
            assertTrue(slaveWheel.contains("message-1"));
        }
    }

    @Test
    void threeTransportFailuresMarkMetadataOutOfSync() throws Exception {
        ReplicaTestSupport.AssignmentStore assignments = assignments();
        ReplicaOperationApplier applier = applier(
                assignments, new ReplicaTestSupport.RecordingWheel("tw-a"));
        AtomicInteger attempts = new AtomicInteger();
        ReplicaTransport transport = operation -> {
            attempts.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("offline"));
        };

        try (DefaultReplicaSync sync = new DefaultReplicaSync(
                "master", assignments, transport, applier, 4, 3, Duration.ofMillis(100))) {
            try {
                sync.sync(operation("op-1", 1)).toCompletableFuture().get(1, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException expected) {
                // Expected failure is asserted through attempts and metadata below.
            }
            assertEquals(3, attempts.get());
            assertEquals("out_of_sync", assignments.current("tw-a").orElseThrow().syncState());
        }
    }

    @Test
    void rebuildReplaysOperationsQueuedWhileReplicaWasOutOfSync() throws Exception {
        ReplicaTestSupport.AssignmentStore assignments = assignments();
        ReplicaTestSupport.RecordingWheel slaveWheel = new ReplicaTestSupport.RecordingWheel("tw-a");
        ReplicaTestSupport.Storage storage = new ReplicaTestSupport.Storage();
        storage.pending = java.util.List.of(
                ReplicaTestSupport.message("message-1", "tw-a", 101));
        ReplicaOperationApplier applier = new ReplicaOperationApplier(
                "slave", new ReplicaTestSupport.Registry(slaveWheel), storage, assignments, 8);
        AtomicBoolean offline = new AtomicBoolean(true);
        ReplicaTransport transport = operation -> offline.get()
                ? CompletableFuture.failedFuture(new IllegalStateException("offline"))
                : applier.apply(operation);

        try (DefaultReplicaSync sync = new DefaultReplicaSync(
                "master", assignments, transport, applier, 8, 3, Duration.ofMillis(100))) {
            try {
                sync.sync(operation("op-1", 1)).toCompletableFuture().get(1, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException expected) {
                // The durable mutation is recovered by the snapshot below.
            }
            var queuedDuringOutage = sync.sync(operation("op-2", 2));
            assertFalse(queuedDuringOutage.toCompletableFuture().isDone());

            offline.set(false);
            RebuildResult rebuild = sync.rebuildFromRedis("tw-a", 7, 1)
                    .toCompletableFuture().get(1, TimeUnit.SECONDS);

            assertEquals(2, queuedDuringOutage.toCompletableFuture()
                    .get(1, TimeUnit.SECONDS).lastAppliedSequence());
            assertEquals(2, applier.lastAppliedSequence("tw-a"));
            assertEquals(1, rebuild.rebuiltMessages());
            assertTrue(slaveWheel.contains("message-1"));
            assertTrue(slaveWheel.contains("message-2"));
            assertEquals("in_sync", assignments.current("tw-a").orElseThrow().syncState());
        } finally {
            applier.close();
        }
    }

    private static ReplicaTestSupport.AssignmentStore assignments() {
        ReplicaTestSupport.AssignmentStore assignments = new ReplicaTestSupport.AssignmentStore();
        assignments.put(new TimeWheelAssignment(
                "tw-a", "master", "slave", "running", "in_sync", 7));
        return assignments;
    }

    private static ReplicaOperationApplier applier(
            ReplicaTestSupport.AssignmentStore assignments,
            ReplicaTestSupport.RecordingWheel wheel) {
        return new ReplicaOperationApplier(
                "slave",
                new ReplicaTestSupport.Registry(wheel),
                new ReplicaTestSupport.Storage(),
                assignments,
                8);
    }

    private static ReplicaOperation operation(String operationId, long sequence) {
        return new ReplicaOperation(
                operationId,
                "tw-a",
                7,
                sequence,
                OperationType.ADD,
                "message-" + sequence,
                100 + sequence);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
