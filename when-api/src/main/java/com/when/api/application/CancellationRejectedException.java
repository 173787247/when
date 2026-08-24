package com.when.api.application;

/** Signals that a message's current state does not permit cancellation. */
public final class CancellationRejectedException extends RuntimeException {
    public CancellationRejectedException() {
        super("message is not pending");
    }
}
