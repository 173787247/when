package com.when.cluster.membership;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.when.core.NodeEndpoint;

/** Non-sensitive identity and endpoint data published for an online When node. */
public record NodeInfo(
        @JsonProperty("node_id") String nodeId,
        String ip,
        @JsonProperty("grpc_port") int grpcPort,
        @JsonProperty("start_time") long startTime,
        double load) {

    public NodeInfo {
        requireSegment(nodeId, "nodeId");
        if (ip == null || ip.isBlank()) {
            throw new IllegalArgumentException("ip must not be blank");
        }
        if (grpcPort < 1 || grpcPort > 65_535) {
            throw new IllegalArgumentException("grpcPort must be between 1 and 65535");
        }
        if (startTime < 0) {
            throw new IllegalArgumentException("startTime must not be negative");
        }
        if (!Double.isFinite(load) || load < 0) {
            throw new IllegalArgumentException("load must be a finite non-negative number");
        }
    }

    public NodeEndpoint endpoint() {
        return new NodeEndpoint(nodeId, ip, grpcPort);
    }

    private static void requireSegment(String value, String field) {
        if (value == null || value.isBlank() || value.indexOf('/') >= 0) {
            throw new IllegalArgumentException(field + " must be a non-blank key segment");
        }
    }
}
