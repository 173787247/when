package com.when.cluster.replica;

import com.when.core.OperationType;
import com.when.core.RebuildResult;
import com.when.core.ReplicaOperation;
import com.when.core.ReplicaSync;
import com.when.core.SyncAck;
import com.when.core.TimeWheelAssignment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class ReplicatedTimeWheelTest {
    @Test
    void mirrorsSuccessfulMutationsWithMonotonicSequenceAndNoPayload() {
        ReplicaTestSupport.AssignmentStore assignments = new ReplicaTestSupport.AssignmentStore();
        assignments.put(new TimeWheelAssignment(
                "tw-a", "master", "slave", "running", "in_sync", 7));
        ReplicaTestSupport.RecordingWheel delegate = new ReplicaTestSupport.RecordingWheel("tw-a");
        List<ReplicaOperation> operations = new ArrayList<>();
        ReplicaSync sync = new ReplicaSync() {
            @Override
            public java.util.concurrent.CompletionStage<SyncAck> sync(ReplicaOperation operation) {
                operations.add(operation);
                return CompletableFuture.completedFuture(
                        new SyncAck(operation.twId(), operation.assignmentVersion(), operation.sequence()));
            }

            @Override
            public void markOutOfSync(String twId, long assignmentVersion, String reason) {
            }

            @Override
            public java.util.concurrent.CompletionStage<RebuildResult> rebuildFromRedis(
                    String twId, long assignmentVersion, long startSequence) {
                throw new UnsupportedOperationException();
            }
        };
        java.util.concurrent.atomic.AtomicInteger operationId = new java.util.concurrent.atomic.AtomicInteger();
        ReplicatedTimeWheel wheel = new ReplicatedTimeWheel(
                "master", delegate, assignments, sync, () -> "op-" + operationId.incrementAndGet());

        wheel.add(ReplicaTestSupport.message("message", "tw-a", 123));
        wheel.remove("message");

        assertEquals(List.of(1L, 2L), operations.stream().map(ReplicaOperation::sequence).toList());
        assertEquals(List.of(OperationType.ADD, OperationType.REMOVE),
                operations.stream().map(ReplicaOperation::type).toList());
        assertEquals(123, operations.get(0).deliverAt());
    }

    @Test
    void staleMasterCannotMutateLocalWheel() {
        ReplicaTestSupport.AssignmentStore assignments = new ReplicaTestSupport.AssignmentStore();
        assignments.put(new TimeWheelAssignment(
                "tw-a", "new-master", "master", "switching", "in_sync", 8));
        ReplicaTestSupport.RecordingWheel delegate = new ReplicaTestSupport.RecordingWheel("tw-a");
        ReplicaSync unused = new ReplicaSync() {
            public java.util.concurrent.CompletionStage<SyncAck> sync(ReplicaOperation operation) {
                throw new AssertionError();
            }
            public void markOutOfSync(String twId, long version, String reason) {
            }
            public java.util.concurrent.CompletionStage<RebuildResult> rebuildFromRedis(
                    String twId, long version, long sequence) {
                throw new AssertionError();
            }
        };
        ReplicatedTimeWheel wheel = new ReplicatedTimeWheel(
                "master", delegate, assignments, unused);

        assertThrows(
                IllegalStateException.class,
                () -> wheel.add(ReplicaTestSupport.message("message", "tw-a", 100)));
        assertEquals(0, delegate.size());
    }
}
