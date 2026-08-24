package com.when.cluster.controller;

/** Revision-ordered Controller input. Nullable node/wheel fields depend on the event type. */
public record ClusterEvent(
        String eventId,
        long revision,
        ControllerEventType type,
        String nodeId,
        String twId) {
    public ClusterEvent {
        eventId = Text.require(eventId, "eventId");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        if (type == null) {
            throw new NullPointerException("type");
        }
        nodeId = Text.optional(nodeId, "nodeId");
        twId = Text.optional(twId, "twId");
        if ((type == ControllerEventType.NODE_JOINED || type == ControllerEventType.NODE_LEFT)
                && nodeId == null) {
            throw new IllegalArgumentException("node events require nodeId");
        }
        if (type == ControllerEventType.SLAVE_OUT_OF_SYNC && twId == null) {
            throw new IllegalArgumentException("out-of-sync events require twId");
        }
    }

    public static ClusterEvent nodeJoined(String eventId, long revision, String nodeId) {
        return new ClusterEvent(eventId, revision, ControllerEventType.NODE_JOINED, nodeId, null);
    }

    public static ClusterEvent nodeLeft(String eventId, long revision, String nodeId) {
        return new ClusterEvent(eventId, revision, ControllerEventType.NODE_LEFT, nodeId, null);
    }

    public static ClusterEvent outOfSync(String eventId, long revision, String twId) {
        return new ClusterEvent(
                eventId, revision, ControllerEventType.SLAVE_OUT_OF_SYNC, null, twId);
    }
}
