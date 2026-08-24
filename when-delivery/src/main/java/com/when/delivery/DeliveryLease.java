package com.when.delivery;

import java.time.Instant;
import java.util.Objects;

/** Durable ownership marker for one downstream delivery attempt. */
public record DeliveryLease(
        String messageId,
        String nodeId,
        String attemptId,
        Instant leaseUntil) {

    public DeliveryLease {
        messageId = requireText(messageId, "messageId");
        nodeId = requireText(nodeId, "nodeId");
        attemptId = requireText(attemptId, "attemptId");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
