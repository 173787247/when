package com.when.delivery;

import com.when.core.DeliveryResult;
import java.time.Instant;
import java.util.Objects;

/** Non-sensitive audit record for one bounded downstream call. */
public record DeliveryAttempt(
        String messageId,
        String attemptId,
        Instant startedAt,
        Instant endedAt,
        boolean success,
        boolean retryable,
        String errorCode,
        long durationMs) {

    public DeliveryAttempt {
        messageId = requireText(messageId, "messageId");
        attemptId = requireText(attemptId, "attemptId");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(endedAt, "endedAt");
        if (endedAt.isBefore(startedAt) || durationMs < 0 || success && retryable) {
            throw new IllegalArgumentException("delivery attempt timing or outcome is invalid");
        }
        errorCode = errorCode == null || errorCode.isBlank() ? null : errorCode;
    }

    public static DeliveryAttempt from(
            String messageId,
            String attemptId,
            Instant startedAt,
            Instant endedAt,
            DeliveryResult result) {
        Objects.requireNonNull(result, "result");
        return new DeliveryAttempt(
                messageId,
                attemptId,
                startedAt,
                endedAt,
                result.success(),
                result.retryable(),
                result.errorCode(),
                result.durationMs());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
