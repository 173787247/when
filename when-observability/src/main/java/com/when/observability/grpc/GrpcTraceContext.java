package com.when.observability.grpc;

import io.grpc.Context;
import java.util.Map;

/** Bridges immutable incoming W3C headers through gRPC's asynchronous Context. */
public final class GrpcTraceContext {
    static final Context.Key<Map<String, String>> INCOMING = Context.key("when-w3c-trace-context");

    private GrpcTraceContext() {
    }

    public static Map<String, String> incoming() {
        Map<String, String> value = INCOMING.get();
        return value == null ? Map.of() : value;
    }
}
