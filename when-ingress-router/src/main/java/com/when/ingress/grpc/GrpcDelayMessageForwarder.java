package com.when.ingress.grpc;

import com.when.api.application.CancelResult;
import com.when.api.application.CancellationRejectedException;
import com.when.api.application.MessageNotFoundException;
import com.when.api.application.SubmitResult;
import com.when.api.grpc.CancelRequest;
import com.when.api.grpc.DelayMessageServiceGrpc;
import com.when.core.NodeEndpoint;
import com.when.ingress.application.DelayMessageForwarder;
import com.when.ingress.application.RoutedSubmit;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Bounded, channel-reusing gRPC implementation of the internal forwarding boundary. */
public final class GrpcDelayMessageForwarder implements DelayMessageForwarder {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final Duration timeout;
    private final Function<NodeEndpoint, ManagedChannel> channelFactory;
    private final Map<Address, ManagedChannel> channels = new ConcurrentHashMap<>();

    public GrpcDelayMessageForwarder() {
        this(DEFAULT_TIMEOUT);
    }

    public GrpcDelayMessageForwarder(Duration timeout) {
        this(timeout, endpoint -> ManagedChannelBuilder
                .forAddress(endpoint.host(), endpoint.grpcPort())
                .usePlaintext()
                .build());
    }

    /** Alternate channel factory supports deterministic in-process acceptance without sockets. */
    public GrpcDelayMessageForwarder(
            Duration timeout, Function<NodeEndpoint, ManagedChannel> channelFactory) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || timeout.toMillis() < 1) {
            throw new IllegalArgumentException("timeout must be at least one millisecond");
        }
        this.channelFactory = Objects.requireNonNull(channelFactory, "channelFactory");
    }

    @Override
    public SubmitResult submit(NodeEndpoint endpoint, RoutedSubmit submit) {
        Objects.requireNonNull(submit, "submit");
        Metadata identity = new Metadata();
        identity.put(ForwardingServerInterceptor.MESSAGE_ID, submit.messageId());
        identity.put(ForwardingServerInterceptor.TRACE_ID, submit.traceId());
        identity.put(ForwardingServerInterceptor.TIME_WHEEL_ID, submit.timeWheelId());
        var response = stub(endpoint)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(identity))
                .submit(GrpcMessageMapper.toRequest(submit.command()));
        return new SubmitResult(
                response.getMessageId(),
                GrpcMessageMapper.fromProto(response.getStatus()),
                response.getDeliverAt());
    }

    @Override
    public CancelResult cancel(NodeEndpoint endpoint, String messageId) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        try {
            var response = stub(endpoint).cancel(
                    CancelRequest.newBuilder().setMessageId(messageId).build());
            return new CancelResult(
                    response.getMessageId(), GrpcMessageMapper.fromProto(response.getStatus()));
        } catch (StatusRuntimeException exception) {
            if (exception.getStatus().getCode() == Status.Code.NOT_FOUND) {
                throw new MessageNotFoundException(messageId);
            }
            if (exception.getStatus().getCode() == Status.Code.FAILED_PRECONDITION) {
                throw new CancellationRejectedException();
            }
            throw exception;
        }
    }

    @Override
    public void close() {
        channels.values().forEach(ManagedChannel::shutdown);
        channels.values().forEach(channel -> {
            try {
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                }
            } catch (InterruptedException exception) {
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        });
        channels.clear();
    }

    private DelayMessageServiceGrpc.DelayMessageServiceBlockingStub stub(NodeEndpoint endpoint) {
        Address address = Address.from(endpoint);
        ManagedChannel channel = channels.computeIfAbsent(address, key ->
                Objects.requireNonNull(channelFactory.apply(endpoint), "channelFactory returned null"));
        return DelayMessageServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private record Address(String host, int port) {
        static Address from(NodeEndpoint endpoint) {
            Objects.requireNonNull(endpoint, "endpoint");
            if (endpoint.host() == null || endpoint.host().isBlank()) {
                throw new IllegalArgumentException("endpoint host must not be blank");
            }
            if (endpoint.grpcPort() < 1 || endpoint.grpcPort() > 65_535) {
                throw new IllegalArgumentException("endpoint port must be between 1 and 65535");
            }
            return new Address(endpoint.host(), endpoint.grpcPort());
        }
    }
}
