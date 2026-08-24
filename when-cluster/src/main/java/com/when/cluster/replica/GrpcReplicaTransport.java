package com.when.cluster.replica;

import com.when.api.grpc.ReplicaOperationType;
import com.when.api.grpc.ReplicaServiceGrpc;
import com.when.api.grpc.ReplicaSyncRequest;
import com.when.api.grpc.ReplicaSyncResponse;
import com.when.core.OperationType;
import com.when.core.ReplicaOperation;
import com.when.core.SyncAck;
import io.grpc.Channel;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** Non-blocking internal gRPC transport for payload-free replica operations. */
public final class GrpcReplicaTransport implements ReplicaTransport {
    private final ReplicaServiceGrpc.ReplicaServiceStub stub;

    public GrpcReplicaTransport(Channel channel, Duration deadline) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(deadline, "deadline");
        if (deadline.isZero() || deadline.isNegative()) {
            throw new IllegalArgumentException("deadline must be positive");
        }
        this.stub = ReplicaServiceGrpc.newStub(channel)
                .withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public CompletionStage<SyncAck> send(ReplicaOperation operation) {
        Objects.requireNonNull(operation, "operation");
        CompletableFuture<SyncAck> result = new CompletableFuture<>();
        stub.sync(toRequest(operation), new StreamObserver<>() {
            @Override
            public void onNext(ReplicaSyncResponse response) {
                result.complete(new SyncAck(
                        response.getTwId(),
                        response.getAssignmentVersion(),
                        response.getLastAppliedSequence()));
            }

            @Override
            public void onError(Throwable error) {
                result.completeExceptionally(error);
            }

            @Override
            public void onCompleted() {
                if (!result.isDone()) {
                    result.completeExceptionally(
                            new IllegalStateException("replica response completed without an acknowledgement"));
                }
            }
        });
        return result;
    }

    private static ReplicaSyncRequest toRequest(ReplicaOperation operation) {
        ReplicaOperationType type = operation.type() == OperationType.ADD
                ? ReplicaOperationType.REPLICA_OPERATION_ADD
                : ReplicaOperationType.REPLICA_OPERATION_REMOVE;
        return ReplicaSyncRequest.newBuilder()
                .setOperationId(operation.operationId())
                .setTwId(operation.twId())
                .setAssignmentVersion(operation.assignmentVersion())
                .setSequence(operation.sequence())
                .setType(type)
                .setMessageId(operation.messageId())
                .setDeliverAt(operation.deliverAt())
                .build();
    }
}
