package com.when.plugin.storage.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.when.delivery.DeliveryAttempt;
import java.time.Instant;

final class DeliveryAttemptJsonCodec {
    private final ObjectMapper mapper = new ObjectMapper();

    String encode(DeliveryAttempt attempt) {
        try {
            return mapper.writeValueAsString(StoredAttempt.from(attempt));
        } catch (JsonProcessingException exception) {
            throw new RedisStorageException(
                    "Unable to serialize delivery attempt for message " + attempt.messageId(), exception);
        }
    }

    DeliveryAttempt decode(String encoded) {
        try {
            return mapper.readValue(encoded, StoredAttempt.class).toAttempt();
        } catch (JsonProcessingException exception) {
            throw new RedisStorageException("Unable to deserialize delivery attempt", exception);
        }
    }

    private record StoredAttempt(
            String messageId,
            String attemptId,
            long startedAt,
            long endedAt,
            boolean success,
            boolean retryable,
            String errorCode,
            long durationMs) {

        private static StoredAttempt from(DeliveryAttempt attempt) {
            return new StoredAttempt(
                    attempt.messageId(),
                    attempt.attemptId(),
                    attempt.startedAt().toEpochMilli(),
                    attempt.endedAt().toEpochMilli(),
                    attempt.success(),
                    attempt.retryable(),
                    attempt.errorCode(),
                    attempt.durationMs());
        }

        private DeliveryAttempt toAttempt() {
            return new DeliveryAttempt(
                    messageId,
                    attemptId,
                    Instant.ofEpochMilli(startedAt),
                    Instant.ofEpochMilli(endedAt),
                    success,
                    retryable,
                    errorCode,
                    durationMs);
        }
    }
}
