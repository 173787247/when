package com.when.core;

/** Values updated atomically together with a state transition; null values mean unchanged. */
public record StatePatch(
        Integer retryCount,
        Long nextAttemptAt,
        Long deliveredAt,
        String lastError) {
}
