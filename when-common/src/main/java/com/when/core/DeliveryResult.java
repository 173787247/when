package com.when.core;

/** Non-sensitive result of one delivery attempt. */
public record DeliveryResult(
        boolean success,
        boolean retryable,
        String errorCode,
        String errorMessage,
        long durationMs) {

    public DeliveryResult {
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must not be negative");
        }
        if (success && retryable) {
            throw new IllegalArgumentException("a successful delivery cannot be retryable");
        }
        errorCode = normalize(errorCode);
        errorMessage = normalize(errorMessage);
        if (success && (errorCode != null || errorMessage != null)) {
            throw new IllegalArgumentException("a successful delivery cannot contain an error");
        }
    }

    /** Compatibility constructor for the original lesson-39 contract. */
    public DeliveryResult(boolean success, boolean retryable, String errorMessage, long durationMs) {
        this(success, retryable, success ? null : "SINK_FAILURE", errorMessage, durationMs);
    }

    public static DeliveryResult success(long durationMs) {
        return new DeliveryResult(true, false, null, null, durationMs);
    }

    public static DeliveryResult retryableFailure(String errorCode, String errorMessage, long durationMs) {
        return new DeliveryResult(false, true, errorCode, errorMessage, durationMs);
    }

    public static DeliveryResult permanentFailure(String errorCode, String errorMessage, long durationMs) {
        return new DeliveryResult(false, false, errorCode, errorMessage, durationMs);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
