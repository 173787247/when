package com.when.observability.grpc;

import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.util.HashMap;
import java.util.Map;

/** Extracts only standard W3C propagation headers; malformed values are rejected by OTel later. */
public final class GrpcTraceServerInterceptor implements ServerInterceptor {
    private static final Metadata.Key<String> TRACEPARENT =
            Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> TRACESTATE =
            Metadata.Key.of("tracestate", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        Map<String, String> carrier = new HashMap<>();
        put(carrier, "traceparent", headers.get(TRACEPARENT));
        put(carrier, "tracestate", headers.get(TRACESTATE));
        return Contexts.interceptCall(
                io.grpc.Context.current().withValue(GrpcTraceContext.INCOMING, Map.copyOf(carrier)),
                call,
                headers,
                next);
    }

    private static void put(Map<String, String> carrier, String key, String value) {
        if (value != null && !value.isBlank() && value.length() <= 512) {
            carrier.put(key, value);
        }
    }
}
