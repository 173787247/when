package com.when.api.application;

/** Signals that the requested message does not exist. */
public final class MessageNotFoundException extends RuntimeException {
    public MessageNotFoundException(String messageId) {
        super("message not found: " + messageId);
    }
}
