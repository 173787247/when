package com.when.core;

/** Address of a When node's internal gRPC service. */
public record NodeEndpoint(String nodeId, String host, int grpcPort) {
}
