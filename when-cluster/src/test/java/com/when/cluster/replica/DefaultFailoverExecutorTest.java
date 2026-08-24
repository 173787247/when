package com.when.cluster.replica;

import com.when.core.PromotionResult;
import com.when.core.TimeWheelAssignment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DefaultFailoverExecutorTest {
    @Test
    void restoresEveryPendingRedisFactBeforeStartingPromotedMaster() {
        ReplicaTestSupport.AssignmentStore assignments = new ReplicaTestSupport.AssignmentStore();
        TimeWheelAssignment assignment = new TimeWheelAssignment(
                "tw-a", "node-2", "node-3", "switching", "in_sync", 8);
        assignments.put(assignment);
        ReplicaTestSupport.RecordingWheel wheel = new ReplicaTestSupport.RecordingWheel("tw-a");
        ReplicaTestSupport.Storage storage = new ReplicaTestSupport.Storage();
        storage.pending = List.of(
                ReplicaTestSupport.message("one", "tw-a", 100),
                ReplicaTestSupport.message("two", "tw-a", 200));

        try (DefaultFailoverExecutor executor = new DefaultFailoverExecutor(
                "node-2", new ReplicaTestSupport.Registry(wheel), storage, assignments)) {
            PromotionResult result = executor.applyAssignment(assignment).toCompletableFuture().join();

            assertTrue(result.promoted());
            assertEquals(2, result.recoveredMessages());
            assertEquals(2, wheel.size());
            assertTrue(wheel.started());
        }
    }

    @Test
    void ignoresAssignmentsOwnedByAnotherNode() {
        ReplicaTestSupport.AssignmentStore assignments = new ReplicaTestSupport.AssignmentStore();
        TimeWheelAssignment assignment = new TimeWheelAssignment(
                "tw-a", "node-2", "node-3", "running", "in_sync", 8);
        assignments.put(assignment);
        ReplicaTestSupport.RecordingWheel wheel = new ReplicaTestSupport.RecordingWheel("tw-a");

        try (DefaultFailoverExecutor executor = new DefaultFailoverExecutor(
                "node-1",
                new ReplicaTestSupport.Registry(wheel),
                new ReplicaTestSupport.Storage(),
                assignments)) {
            PromotionResult result = executor.applyAssignment(assignment).toCompletableFuture().join();

            assertFalse(result.promoted());
            assertFalse(wheel.started());
        }
    }

    @Test
    void assignmentChangeDuringRedisRecoveryPreventsSchedulerStart() throws Exception {
        ReplicaTestSupport.AssignmentStore assignments = new ReplicaTestSupport.AssignmentStore();
        TimeWheelAssignment assignment = new TimeWheelAssignment(
                "tw-a", "node-2", "node-3", "switching", "out_of_sync", 8);
        assignments.put(assignment);
        ReplicaTestSupport.RecordingWheel wheel = new ReplicaTestSupport.RecordingWheel("tw-a");
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ReplicaTestSupport.Storage storage = new ReplicaTestSupport.Storage() {
            @Override
            public List<com.when.core.Message> loadPendingByTimeWheel(String timeWheelId) {
                loading.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return List.of(ReplicaTestSupport.message("one", "tw-a", 100));
            }
        };

        try (DefaultFailoverExecutor executor = new DefaultFailoverExecutor(
                "node-2", new ReplicaTestSupport.Registry(wheel), storage, assignments)) {
            var promotion = executor.applyAssignment(assignment);
            assertTrue(loading.await(1, TimeUnit.SECONDS));
            assignments.put(new TimeWheelAssignment(
                    "tw-a", "node-4", "node-3", "switching", "in_sync", 9));
            release.countDown();

            assertThrows(CompletionException.class, () -> promotion.toCompletableFuture().join());
            assertFalse(wheel.started());
        }
    }
}
