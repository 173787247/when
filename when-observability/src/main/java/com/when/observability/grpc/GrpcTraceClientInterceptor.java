package com.when.observability.grpc;

import com.when.observability.TraceOperations;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Injects current W3C context into an outbound gRPC call. */
public final class GrpcTraceClientInterceptor implements ClientInterceptor {
    private final TraceOperations traces;

    public GrpcTraceClientInterceptor(TraceOperations traces) {
        this.traces = Objects.requireNonNull(traces, "traces");
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions options, Channel next) {
        ClientCall<ReqT, RespT> delegate = next.newCall(method, options);
        return new ForwardingClientCall.SimpleForwardingClientCall<>(delegate) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                for (Map.Entry<String, String> entry : traces.injectCurrentContext().entrySet()) {
                    String name = entry.getKey().toLowerCase(Locale.ROOT);
                    if ("traceparent".equals(name) || "tracestate".equals(name)) {
                        headers.put(Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER), entry.getValue());
                    }
                }
                super.start(responseListener, headers);
            }
        };
    }
}
