package com.when.api.grpc;

import static io.grpc.MethodDescriptor.generateFullMethodName;

import io.grpc.BindableService;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.MethodDescriptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServiceDescriptor;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.AbstractAsyncStub;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;

/** Unary gRPC binding for the internal {@code when.v1.ReplicaService}. */
public final class ReplicaServiceGrpc {
    public static final String SERVICE_NAME = "when.v1.ReplicaService";

    private static volatile MethodDescriptor<ReplicaSyncRequest, ReplicaSyncResponse> syncMethod;
    private static volatile ServiceDescriptor serviceDescriptor;

    private ReplicaServiceGrpc() {
    }

    public static MethodDescriptor<ReplicaSyncRequest, ReplicaSyncResponse> getSyncMethod() {
        MethodDescriptor<ReplicaSyncRequest, ReplicaSyncResponse> local = syncMethod;
        if (local == null) {
            synchronized (ReplicaServiceGrpc.class) {
                local = syncMethod;
                if (local == null) {
                    local = MethodDescriptor.<ReplicaSyncRequest, ReplicaSyncResponse>newBuilder()
                            .setType(MethodDescriptor.MethodType.UNARY)
                            .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Sync"))
                            .setRequestMarshaller(
                                    ProtoUtils.marshaller(ReplicaSyncRequest.getDefaultInstance()))
                            .setResponseMarshaller(
                                    ProtoUtils.marshaller(ReplicaSyncResponse.getDefaultInstance()))
                            .build();
                    syncMethod = local;
                }
            }
        }
        return local;
    }

    public static ReplicaServiceStub newStub(Channel channel) {
        return ReplicaServiceStub.newStub(
                (selected, options) -> new ReplicaServiceStub(selected, options), channel);
    }

    public abstract static class ReplicaServiceImplBase implements BindableService {
        public void sync(
                ReplicaSyncRequest request,
                StreamObserver<ReplicaSyncResponse> responseObserver) {
            ServerCalls.asyncUnimplementedUnaryCall(getSyncMethod(), responseObserver);
        }

        @Override
        public final ServerServiceDefinition bindService() {
            return ServerServiceDefinition.builder(getServiceDescriptor())
                    .addMethod(
                            getSyncMethod(),
                            ServerCalls.asyncUnaryCall(new ServerCalls.UnaryMethod<>() {
                                @Override
                                public void invoke(
                                        ReplicaSyncRequest request,
                                        StreamObserver<ReplicaSyncResponse> observer) {
                                    sync(request, observer);
                                }
                            }))
                    .build();
        }
    }

    public static final class ReplicaServiceStub extends AbstractAsyncStub<ReplicaServiceStub> {
        private ReplicaServiceStub(Channel channel, CallOptions callOptions) {
            super(channel, callOptions);
        }

        @Override
        protected ReplicaServiceStub build(Channel channel, CallOptions callOptions) {
            return new ReplicaServiceStub(channel, callOptions);
        }

        public void sync(
                ReplicaSyncRequest request,
                StreamObserver<ReplicaSyncResponse> responseObserver) {
            ClientCalls.asyncUnaryCall(
                    getChannel().newCall(getSyncMethod(), getCallOptions()),
                    request,
                    responseObserver);
        }
    }

    public static ServiceDescriptor getServiceDescriptor() {
        ServiceDescriptor local = serviceDescriptor;
        if (local == null) {
            synchronized (ReplicaServiceGrpc.class) {
                local = serviceDescriptor;
                if (local == null) {
                    local = ServiceDescriptor.newBuilder(SERVICE_NAME)
                            .addMethod(getSyncMethod())
                            .build();
                    serviceDescriptor = local;
                }
            }
        }
        return local;
    }
}
