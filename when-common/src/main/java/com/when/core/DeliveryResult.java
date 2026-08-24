package com.when.core;

/** Non-sensitive result of one delivery attempt. */
public record DeliveryResult(
        boolean success,
        boolean retryable,
        String errorMessage,
        long durationMs) {
}
