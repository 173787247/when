package com.when.api.grpc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.protobuf.ByteString;
import com.when.api.application.CancelResult;
import com.when.api.application.CancellationRejectedException;
import com.when.api.application.DelayMessageHandler;
import com.when.api.application.MessageNotFoundException;
import com.when.api.application.MessageView;
import com.when.api.application.SubmitCommand;
import com.when.api.application.SubmitResult;
import com.when.common.proto.HttpSinkConfig;
import com.when.common.proto.MessageStatus;
import com.when.common.proto.SinkConfig;
import com.when.common.proto.SinkType;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DelayMessageServiceImplTest {
    private static final long NOW = 1_750_000_000_000L;

    @Test
    void delegatesSubmitAndMapsEveryResponseField() {
        RecordingHandler handler = new RecordingHandler();
        var service = service(handler);
        var observer = new CapturingObserver<SubmitResponse>();

        service.submit(validSubmit(), observer);

        assertEquals(1, handler.submitCalls);
        assertEquals(NOW + 5_000, handler.lastCommand.deliverAt());
        assertEquals(com.when.core.SinkType.HTTP, handler.lastCommand.sinkType());
        assertArrayEquals(new byte[] {1, 2, 3}, handler.lastCommand.payload());
        assertEquals("orders", handler.lastCommand.businessTag());
        assertEquals("msg-1", observer.value.getMessageId());
        assertEquals(MessageStatus.PENDING, observer.value.getStatus());
        assertEquals(NOW + 5_000, observer.value.getDeliverAt());
        assertEquals(1, observer.completed);
    }

    @Test
    void delegatesQueryAndCancelAndMapsFields() {
        RecordingHandler handler = new RecordingHandler();
        var service = service(handler);
        var queryObserver = new CapturingObserver<QueryResponse>();
        var cancelObserver = new CapturingObserver<CancelResponse>();

        service.query(QueryRequest.newBuilder().setMessageId("msg-1").build(), queryObserver);
        service.cancel(CancelRequest.newBuilder().setMessageId("msg-1").build(), cancelObserver);

        assertEquals(1, handler.queryCalls);
        assertEquals(1, handler.cancelCalls);
        assertEquals(MessageStatus.PENDING, queryObserver.value.getStatus());
        assertEquals(2, queryObserver.value.getRetryCount());
        assertEquals(SinkType.HTTP, queryObserver.value.getSinkType());
        assertEquals("orders", queryObserver.value.getBusinessTag());
        assertEquals(MessageStatus.CANCELLED, cancelObserver.value.getStatus());
    }

    @Test
    void mapsValidationNotFoundAndCancellationRejectionToGrpcStatuses() {
        RecordingHandler handler = new RecordingHandler();
        var service = service(handler);
        var invalid = new CapturingObserver<SubmitResponse>();
        service.submit(SubmitRequest.getDefaultInstance(), invalid);
        assertStatus(Status.Code.INVALID_ARGUMENT, invalid.error);
        assertEquals(0, handler.submitCalls);

        handler.queryFailure = new MessageNotFoundException("msg-missing");
        var missing = new CapturingObserver<QueryResponse>();
        service.query(QueryRequest.newBuilder().setMessageId("msg-missing").build(), missing);
        assertStatus(Status.Code.NOT_FOUND, missing.error);

        handler.cancelFailure = new CancellationRejectedException();
        var rejected = new CapturingObserver<CancelResponse>();
        service.cancel(CancelRequest.newBuilder().setMessageId("msg-1").build(), rejected);
        assertStatus(Status.Code.FAILED_PRECONDITION, rejected.error);
    }

    @Test
    void unexpectedFailuresAreSanitized() {
        RecordingHandler handler = new RecordingHandler();
        handler.queryFailure = new IllegalStateException("payload=do-not-expose");
        var observer = new CapturingObserver<QueryResponse>();

        service(handler).query(
                QueryRequest.newBuilder().setMessageId("msg-1").build(), observer);

        assertStatus(Status.Code.INTERNAL, observer.error);
        Status status = Status.fromThrowable(observer.error);
        assertEquals("request processing failed", status.getDescription());
    }

    private static DelayMessageServiceImpl service(RecordingHandler handler) {
        return new DelayMessageServiceImpl(
                handler, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    private static SubmitRequest validSubmit() {
        return SubmitRequest.newBuilder()
                .setDelaySeconds(5)
                .setSinkType(SinkType.HTTP)
                .setSinkConfig(SinkConfig.newBuilder().setHttp(HttpSinkConfig.newBuilder()
                        .setUrl("https://example.internal/callback")
                        .setMethod("POST")))
                .setPayload(ByteString.copyFrom(new byte[] {1, 2, 3}))
                .setBusinessTag("orders")
                .build();
    }

    private static void assertStatus(Status.Code code, Throwable throwable) {
        assertNotNull(throwable);
        assertEquals(code, Status.fromThrowable(throwable).getCode());
    }

    private static final class CapturingObserver<T> implements StreamObserver<T> {
        private T value;
        private Throwable error;
        private int completed;

        @Override
        public void onNext(T value) {
            this.value = value;
        }

        @Override
        public void onError(Throwable throwable) {
            this.error = throwable;
        }

        @Override
        public void onCompleted() {
            completed++;
        }
    }

    private static final class RecordingHandler implements DelayMessageHandler {
        private int submitCalls;
        private int queryCalls;
        private int cancelCalls;
        private SubmitCommand lastCommand;
        private RuntimeException queryFailure;
        private RuntimeException cancelFailure;

        @Override
        public SubmitResult submit(SubmitCommand command) {
            submitCalls++;
            lastCommand = command;
            return new SubmitResult("msg-1", com.when.core.MessageStatus.PENDING, command.deliverAt());
        }

        @Override
        public MessageView query(String messageId) {
            queryCalls++;
            if (queryFailure != null) {
                throw queryFailure;
            }
            return new MessageView(
                    messageId,
                    com.when.core.MessageStatus.PENDING,
                    NOW,
                    NOW + 5_000,
                    0,
                    2,
                    "temporary failure",
                    com.when.core.SinkType.HTTP,
                    "orders");
        }

        @Override
        public CancelResult cancel(String messageId) {
            cancelCalls++;
            if (cancelFailure != null) {
                throw cancelFailure;
            }
            return new CancelResult(messageId, com.when.core.MessageStatus.CANCELLED);
        }
    }
}
