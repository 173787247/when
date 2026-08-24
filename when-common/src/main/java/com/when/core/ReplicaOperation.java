package com.when.core;

/** Payload-free scheduling mutation sent over the internal replica channel. */
public record ReplicaOperation(
        String operationId,
        String twId,
        long assignmentVersion,
        long sequence,
        OperationType type,
        String messageId,
        long deliverAt) {

    public ReplicaOperation {
        operationId = requireText(operationId, "operationId");
        twId = requireText(twId, "twId");
        if (assignmentVersion < 0) {
            throw new IllegalArgumentException("assignmentVersion must not be negative");
        }
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        messageId = requireText(messageId, "messageId");
        if (type == OperationType.ADD && deliverAt < 0) {
            throw new IllegalArgumentException("deliverAt must not be negative for ADD");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
