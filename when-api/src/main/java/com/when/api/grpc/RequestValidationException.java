package com.when.api.grpc;

/** Non-sensitive validation failure suitable for an INVALID_ARGUMENT description. */
public final class RequestValidationException extends IllegalArgumentException {
    public RequestValidationException(String message) {
        super(message);
    }
}
