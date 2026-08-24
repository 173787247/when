package com.when.cluster.replica;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.when.api.grpc.ReplicaOperationType;
import com.when.api.grpc.ReplicaSyncRequest;
import com.when.api.grpc.ReplicaSyncResponse;
import com.when.core.TimeWheelAssignment;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

class ReplicaServiceImplTest {
    @Test
    void mapsPayloadFreeGrpcOperationToOrderedSlaveApplier() {
        ReplicaTestSupport.AssignmentStore assignments = new ReplicaTestSupport.AssignmentStore();
        assignments.put(new TimeWheelAssignment(
                "tw-a", "master", "slave", "running", "in_sync", 7));
        ReplicaTestSupport.RecordingWheel wheel = new ReplicaTestSupport.RecordingWheel("tw-a");
        ReplicaOperationApplier applier = new ReplicaOperationApplier(
                "slave",
                new ReplicaTestSupport.Registry(wheel),
                new ReplicaTestSupport.Storage(),
                assignments);
        CapturingObserver observer = new CapturingObserver();

        new ReplicaServiceImpl(applier).sync(
                ReplicaSyncRequest.newBuilder()
                        .setOperationId("op-1")
                        .setTwId("tw-a")
                        .setAssignmentVersion(7)
                        .setSequence(1)
                        .setType(ReplicaOperationType.REPLICA_OPERATION_ADD)
                        .setMessageId("message-1")
                        .setDeliverAt(123)
                        .build(),
                observer);

        assertNull(observer.failure);
        assertTrue(observer.completed);
        assertEquals(1, observer.response.getLastAppliedSequence());
        assertTrue(wheel.contains("message-1"));
    }

    private static final class CapturingObserver implements StreamObserver<ReplicaSyncResponse> {
        private ReplicaSyncResponse response;
        private Throwable failure;
        private boolean completed;

        @Override
        public void onNext(ReplicaSyncResponse value) {
            response = value;
        }

        @Override
        public void onError(Throwable error) {
            failure = error;
        }

        @Override
        public void onCompleted() {
            completed = true;
        }
    }
}
