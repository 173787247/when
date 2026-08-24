package com.when.cluster.replica;

import com.when.api.grpc.ReplicaOperationType;
import com.when.api.grpc.ReplicaServiceGrpc;
import com.when.api.grpc.ReplicaSyncRequest;
import com.when.api.grpc.ReplicaSyncResponse;
import com.when.core.OperationType;
import com.when.core.ReplicaOperation;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;

/** Slave-side gRPC endpoint that delegates all ordering and fencing to the applier. */
public final class ReplicaServiceImpl extends ReplicaServiceGrpc.ReplicaServiceImplBase {
    private final ReplicaOperationApplier applier;

    public ReplicaServiceImpl(ReplicaOperationApplier applier) {
        this.applier = Objects.requireNonNull(applier, "applier");
    }

    @Override
    public void sync(
            ReplicaSyncRequest request,
            StreamObserver<ReplicaSyncResponse> responseObserver) {
        final ReplicaOperation operation;
        try {
            operation = fromRequest(request);
        } catch (IllegalArgumentException exception) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("invalid replica operation")
                    .asRuntimeException());
            return;
        }
        applier.apply(operation).whenComplete((ack, failure) -> {
            if (failure != null) {
                responseObserver.onError(Status.FAILED_PRECONDITION
                        .withDescription("replica operation was rejected")
                        .asRuntimeException());
                return;
            }
            responseObserver.onNext(ReplicaSyncResponse.newBuilder()
                    .setTwId(ack.twId())
                    .setAssignmentVersion(ack.assignmentVersion())
                    .setLastAppliedSequence(ack.lastAppliedSequence())
                    .build());
            responseObserver.onCompleted();
        });
    }

    private static ReplicaOperation fromRequest(ReplicaSyncRequest request) {
        Objects.requireNonNull(request, "request");
        OperationType type;
        if (request.getType() == ReplicaOperationType.REPLICA_OPERATION_ADD) {
            type = OperationType.ADD;
        } else if (request.getType() == ReplicaOperationType.REPLICA_OPERATION_REMOVE) {
            type = OperationType.REMOVE;
        } else {
            throw new IllegalArgumentException("replica operation type is required");
        }
        return new ReplicaOperation(
                request.getOperationId(),
                request.getTwId(),
                request.getAssignmentVersion(),
                request.getSequence(),
                type,
                request.getMessageId(),
                request.getDeliverAt());
    }
}
