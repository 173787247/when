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
import io.grpc.stub.AbstractBlockingStub;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;

/**
 * gRPC-Java service bindings for {@code when.v1.DelayMessageService}.
 *
 * <p>The protobuf message classes remain generated from {@code when-api.proto}. This small binding
 * is deliberately equivalent to protoc-gen-grpc-java's unary-service output so the module can be
 * built on hosts where the native generator is unavailable.
 */
public final class DelayMessageServiceGrpc {
    public static final String SERVICE_NAME = "when.v1.DelayMessageService";

    private static volatile MethodDescriptor<SubmitRequest, SubmitResponse> submitMethod;
    private static volatile MethodDescriptor<QueryRequest, QueryResponse> queryMethod;
    private static volatile MethodDescriptor<CancelRequest, CancelResponse> cancelMethod;
    private static volatile ServiceDescriptor serviceDescriptor;

    private DelayMessageServiceGrpc() {
    }

    public static MethodDescriptor<SubmitRequest, SubmitResponse> getSubmitMethod() {
        MethodDescriptor<SubmitRequest, SubmitResponse> local = submitMethod;
        if (local == null) {
            synchronized (DelayMessageServiceGrpc.class) {
                local = submitMethod;
                if (local == null) {
                    local = MethodDescriptor.<SubmitRequest, SubmitResponse>newBuilder()
                            .setType(MethodDescriptor.MethodType.UNARY)
                            .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Submit"))
                            .setRequestMarshaller(ProtoUtils.marshaller(SubmitRequest.getDefaultInstance()))
                            .setResponseMarshaller(ProtoUtils.marshaller(SubmitResponse.getDefaultInstance()))
                            .build();
                    submitMethod = local;
                }
            }
        }
        return local;
    }

    public static MethodDescriptor<QueryRequest, QueryResponse> getQueryMethod() {
        MethodDescriptor<QueryRequest, QueryResponse> local = queryMethod;
        if (local == null) {
            synchronized (DelayMessageServiceGrpc.class) {
                local = queryMethod;
                if (local == null) {
                    local = MethodDescriptor.<QueryRequest, QueryResponse>newBuilder()
                            .setType(MethodDescriptor.MethodType.UNARY)
                            .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Query"))
                            .setRequestMarshaller(ProtoUtils.marshaller(QueryRequest.getDefaultInstance()))
                            .setResponseMarshaller(ProtoUtils.marshaller(QueryResponse.getDefaultInstance()))
                            .build();
                    queryMethod = local;
                }
            }
        }
        return local;
    }

    public static MethodDescriptor<CancelRequest, CancelResponse> getCancelMethod() {
        MethodDescriptor<CancelRequest, CancelResponse> local = cancelMethod;
        if (local == null) {
            synchronized (DelayMessageServiceGrpc.class) {
                local = cancelMethod;
                if (local == null) {
                    local = MethodDescriptor.<CancelRequest, CancelResponse>newBuilder()
                            .setType(MethodDescriptor.MethodType.UNARY)
                            .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Cancel"))
                            .setRequestMarshaller(ProtoUtils.marshaller(CancelRequest.getDefaultInstance()))
                            .setResponseMarshaller(ProtoUtils.marshaller(CancelResponse.getDefaultInstance()))
                            .build();
                    cancelMethod = local;
                }
            }
        }
        return local;
    }

    public static DelayMessageServiceStub newStub(Channel channel) {
        return DelayMessageServiceStub.newStub(
                new io.grpc.stub.AbstractStub.StubFactory<DelayMessageServiceStub>() {
                    @Override
                    public DelayMessageServiceStub newStub(Channel selected, CallOptions options) {
                        return new DelayMessageServiceStub(selected, options);
                    }
                }, channel);
    }

    public static DelayMessageServiceBlockingStub newBlockingStub(Channel channel) {
        return DelayMessageServiceBlockingStub.newStub(
                new io.grpc.stub.AbstractStub.StubFactory<DelayMessageServiceBlockingStub>() {
                    @Override
                    public DelayMessageServiceBlockingStub newStub(
                            Channel selected, CallOptions options) {
                        return new DelayMessageServiceBlockingStub(selected, options);
                    }
                }, channel);
    }

    public abstract static class DelayMessageServiceImplBase implements BindableService {
        public void submit(
                SubmitRequest request, StreamObserver<SubmitResponse> responseObserver) {
            ServerCalls.asyncUnimplementedUnaryCall(getSubmitMethod(), responseObserver);
        }

        public void query(QueryRequest request, StreamObserver<QueryResponse> responseObserver) {
            ServerCalls.asyncUnimplementedUnaryCall(getQueryMethod(), responseObserver);
        }

        public void cancel(CancelRequest request, StreamObserver<CancelResponse> responseObserver) {
            ServerCalls.asyncUnimplementedUnaryCall(getCancelMethod(), responseObserver);
        }

        @Override
        public final ServerServiceDefinition bindService() {
            return ServerServiceDefinition.builder(getServiceDescriptor())
                    .addMethod(
                            getSubmitMethod(),
                            ServerCalls.asyncUnaryCall(new ServerCalls.UnaryMethod<>() {
                                @Override
                                public void invoke(
                                        SubmitRequest request,
                                        StreamObserver<SubmitResponse> observer) {
                                    submit(request, observer);
                                }
                            }))
                    .addMethod(
                            getQueryMethod(),
                            ServerCalls.asyncUnaryCall(new ServerCalls.UnaryMethod<>() {
                                @Override
                                public void invoke(
                                        QueryRequest request,
                                        StreamObserver<QueryResponse> observer) {
                                    query(request, observer);
                                }
                            }))
                    .addMethod(
                            getCancelMethod(),
                            ServerCalls.asyncUnaryCall(new ServerCalls.UnaryMethod<>() {
                                @Override
                                public void invoke(
                                        CancelRequest request,
                                        StreamObserver<CancelResponse> observer) {
                                    cancel(request, observer);
                                }
                            }))
                    .build();
        }
    }

    public static final class DelayMessageServiceStub
            extends AbstractAsyncStub<DelayMessageServiceStub> {
        private DelayMessageServiceStub(Channel channel, CallOptions callOptions) {
            super(channel, callOptions);
        }

        @Override
        protected DelayMessageServiceStub build(Channel channel, CallOptions callOptions) {
            return new DelayMessageServiceStub(channel, callOptions);
        }

        public void submit(SubmitRequest request, StreamObserver<SubmitResponse> observer) {
            ClientCalls.asyncUnaryCall(
                    getChannel().newCall(getSubmitMethod(), getCallOptions()), request, observer);
        }

        public void query(QueryRequest request, StreamObserver<QueryResponse> observer) {
            ClientCalls.asyncUnaryCall(
                    getChannel().newCall(getQueryMethod(), getCallOptions()), request, observer);
        }

        public void cancel(CancelRequest request, StreamObserver<CancelResponse> observer) {
            ClientCalls.asyncUnaryCall(
                    getChannel().newCall(getCancelMethod(), getCallOptions()), request, observer);
        }
    }

    public static final class DelayMessageServiceBlockingStub
            extends AbstractBlockingStub<DelayMessageServiceBlockingStub> {
        private DelayMessageServiceBlockingStub(Channel channel, CallOptions callOptions) {
            super(channel, callOptions);
        }

        @Override
        protected DelayMessageServiceBlockingStub build(Channel channel, CallOptions callOptions) {
            return new DelayMessageServiceBlockingStub(channel, callOptions);
        }

        public SubmitResponse submit(SubmitRequest request) {
            return ClientCalls.blockingUnaryCall(
                    getChannel(), getSubmitMethod(), getCallOptions(), request);
        }

        public QueryResponse query(QueryRequest request) {
            return ClientCalls.blockingUnaryCall(
                    getChannel(), getQueryMethod(), getCallOptions(), request);
        }

        public CancelResponse cancel(CancelRequest request) {
            return ClientCalls.blockingUnaryCall(
                    getChannel(), getCancelMethod(), getCallOptions(), request);
        }
    }

    public static ServiceDescriptor getServiceDescriptor() {
        ServiceDescriptor local = serviceDescriptor;
        if (local == null) {
            synchronized (DelayMessageServiceGrpc.class) {
                local = serviceDescriptor;
                if (local == null) {
                    local = ServiceDescriptor.newBuilder(SERVICE_NAME)
                            .addMethod(getSubmitMethod())
                            .addMethod(getQueryMethod())
                            .addMethod(getCancelMethod())
                            .build();
                    serviceDescriptor = local;
                }
            }
        }
        return local;
    }
}
