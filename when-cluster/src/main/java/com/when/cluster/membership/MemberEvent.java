package com.when.cluster.membership;

import java.util.Objects;
import java.util.Optional;

/** A node membership change observed below {@code /when/nodes/}. */
public record MemberEvent(Type type, String nodeId, Optional<NodeInfo> nodeInfo) {
    public MemberEvent {
        Objects.requireNonNull(type, "type");
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        nodeInfo = Objects.requireNonNull(nodeInfo, "nodeInfo");
        if (type == Type.PUT && nodeInfo.isEmpty()) {
            throw new IllegalArgumentException("PUT events must contain nodeInfo");
        }
        if (nodeInfo.isPresent() && !nodeId.equals(nodeInfo.orElseThrow().nodeId())) {
            throw new IllegalArgumentException("event nodeId must match nodeInfo");
        }
    }

    public enum Type {
        PUT,
        DELETE
    }
}
