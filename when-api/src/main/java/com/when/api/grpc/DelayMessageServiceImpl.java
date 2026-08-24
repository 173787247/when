package com.when.api.grpc;

import com.when.api.application.CancelResult;
import com.when.api.application.CancellationRejectedException;
import com.when.api.application.DelayMessageHandler;
import com.when.api.application.MessageNotFoundException;
import com.when.api.application.MessageView;
import com.when.api.application.SubmitCommand;
import com.when.api.application.SubmitResult;
import com.when.core.MessageStatus;
import com.when.core.SinkType;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Clock;
import java.util.Objects;

/** Thin gRPC adapter: validate, map, delegate, and map the application result back to protobuf. */
public final class DelayMessageServiceImpl extends DelayMessageServiceGrpc.DelayMessageServiceImplBase {
    private final DelayMessageHandler handler;
    private final SubmitRequestValidator validator;

    public DelayMessageServiceImpl(DelayMessageHandler handler) {
        this(handler, Clock.systemUTC());
    }

    public DelayMessageServiceImpl(DelayMessageHandler handler, Clock clock) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.validator = new SubmitRequestValidator(clock);
    }

    @Override
    public void submit(SubmitRequest request, StreamObserver<SubmitResponse> responseObserver) {
        try {
            ValidatedSubmit validated = validator.validate(request);
            SubmitResult result = handler.submit(new SubmitCommand(
                    validated.deliverAt(),
                    validated.sinkType(),
                    validated.sinkConfig(),
                    validated.payload(),
                    validated.businessTag()));
            responseObserver.onNext(SubmitResponse.newBuilder()
                    .setMessageId(result.messageId())
                    .setStatus(toProto(result.status()))
                    .setDeliverAt(result.deliverAt())
                    .build());
            responseObserver.onCompleted();
        } catch (RequestValidationException exception) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription(exception.getMessage())
                    .asRuntimeException());
        } catch (RuntimeException exception) {
            responseObserver.onError(internalError());
        }
    }

    @Override
    public void query(QueryRequest request, StreamObserver<QueryResponse> responseObserver) {
        if (request.getMessageId().isBlank()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("message_id is required")
                    .asRuntimeException());
            return;
        }
        try {
            MessageView view = handler.query(request.getMessageId());
            if (view == null) {
                throw new MessageNotFoundException(request.getMessageId());
            }
            responseObserver.onNext(QueryResponse.newBuilder()
                    .setMessageId(view.messageId())
                    .setStatus(toProto(view.status()))
                    .setCreatedAt(view.createdAt())
                    .setDeliverAt(view.deliverAt())
                    .setDeliveredAt(view.deliveredAt())
                    .setRetryCount(view.retryCount())
                    .setLastError(nullToEmpty(view.lastError()))
                    .setSinkType(toProto(view.sinkType()))
                    .setBusinessTag(nullToEmpty(view.businessTag()))
                    .build());
            responseObserver.onCompleted();
        } catch (MessageNotFoundException exception) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("message not found")
                    .asRuntimeException());
        } catch (RuntimeException exception) {
            responseObserver.onError(internalError());
        }
    }

    @Override
    public void cancel(CancelRequest request, StreamObserver<CancelResponse> responseObserver) {
        if (request.getMessageId().isBlank()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("message_id is required")
                    .asRuntimeException());
            return;
        }
        try {
            CancelResult result = handler.cancel(request.getMessageId());
            responseObserver.onNext(CancelResponse.newBuilder()
                    .setMessageId(result.messageId())
                    .setStatus(toProto(result.status()))
                    .build());
            responseObserver.onCompleted();
        } catch (MessageNotFoundException exception) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("message not found")
                    .asRuntimeException());
        } catch (CancellationRejectedException exception) {
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription("message is not pending")
                    .asRuntimeException());
        } catch (RuntimeException exception) {
            responseObserver.onError(internalError());
        }
    }

    private static io.grpc.StatusRuntimeException internalError() {
        return Status.INTERNAL.withDescription("request processing failed").asRuntimeException();
    }

    private static com.when.common.proto.MessageStatus toProto(MessageStatus status) {
        if (status == null) {
            return com.when.common.proto.MessageStatus.MESSAGE_STATUS_UNSPECIFIED;
        }
        return switch (status) {
            case PENDING -> com.when.common.proto.MessageStatus.PENDING;
            case DELIVERING -> com.when.common.proto.MessageStatus.DELIVERING;
            case DELIVERED -> com.when.common.proto.MessageStatus.DELIVERED;
            case FAILED -> com.when.common.proto.MessageStatus.FAILED;
            case CANCELLED -> com.when.common.proto.MessageStatus.CANCELLED;
        };
    }

    private static com.when.common.proto.SinkType toProto(SinkType sinkType) {
        if (sinkType == null) {
            return com.when.common.proto.SinkType.SINK_TYPE_UNSPECIFIED;
        }
        return switch (sinkType) {
            case HTTP -> com.when.common.proto.SinkType.HTTP;
            case KAFKA -> com.when.common.proto.SinkType.KAFKA;
        };
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
