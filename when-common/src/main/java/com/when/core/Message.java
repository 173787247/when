package com.when.core;

/**
 * Canonical in-process representation of a delayed message.
 *
 * <p>All timestamps use Unix epoch milliseconds. Redis is the authority for instances of this
 * model; in-memory schedulers retain only rebuildable references.
 */
public record Message(
        String messageId,
        long createdAt,
        long deliverAt,
        String timeWheelId,
        SinkType sinkType,
        SinkConfig sinkConfig,
        byte[] payload,
        String businessTag,
        MessageStatus status,
        int retryCount,
        long nextAttemptAt,
        long deliveredAt,
        String lastError,
        String traceId) {

    public Message {
        payload = payload == null ? null : payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload == null ? null : payload.clone();
    }
}
