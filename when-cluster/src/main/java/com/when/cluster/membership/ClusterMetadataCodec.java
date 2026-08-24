package com.when.cluster.membership;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** JSON codec for membership values; it never contains credentials or payload data. */
public final class ClusterMetadataCodec {
    private final ObjectMapper mapper = new ObjectMapper();

    public String encodeNode(NodeInfo node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("node metadata cannot be encoded", e);
        }
    }

    /**
     * Decodes current membership JSON and accepts lesson 42's {@code port} field for a clean
     * rolling handoff. The node id is still checked against the authoritative key segment.
     */
    public NodeInfo decodeNode(String expectedNodeId, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("node metadata value must not be blank");
        }
        try {
            JsonNode root = mapper.readTree(value);
            String storedNodeId = text(root, "node_id", expectedNodeId);
            if (!expectedNodeId.equals(storedNodeId)) {
                throw new IllegalArgumentException("node metadata id does not match its key");
            }
            JsonNode grpcPort = root.has("grpc_port") ? root.get("grpc_port") : root.get("port");
            if (grpcPort == null || !grpcPort.canConvertToInt()) {
                throw new IllegalArgumentException("node metadata grpc_port is required");
            }
            return new NodeInfo(
                    storedNodeId,
                    text(root, "ip", null),
                    grpcPort.intValue(),
                    requiredLong(root, "start_time"),
                    requiredDouble(root, "load"));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("node metadata cannot be decoded", e);
        }
    }

    private static String text(JsonNode root, String name, String fallback) {
        JsonNode value = root.get(name);
        return value == null || value.isNull() ? fallback : value.asText();
    }

    private static long requiredLong(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isIntegralNumber()) {
            throw new IllegalArgumentException("node metadata " + name + " is required");
        }
        return value.longValue();
    }

    private static double requiredDouble(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException("node metadata " + name + " is required");
        }
        return value.doubleValue();
    }
}
