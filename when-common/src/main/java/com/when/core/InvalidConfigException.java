package com.when.core;

/** Raised when a sink receives configuration of the wrong type or with invalid fields. */
public final class InvalidConfigException extends IllegalArgumentException {
    public InvalidConfigException(String message) {
        super(message);
    }

    public InvalidConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
