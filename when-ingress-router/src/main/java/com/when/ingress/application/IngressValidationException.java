package com.when.ingress.application;

/** Non-sensitive application validation failure. */
public final class IngressValidationException extends IllegalArgumentException {
    public IngressValidationException(String message) {
        super(message);
    }
}
