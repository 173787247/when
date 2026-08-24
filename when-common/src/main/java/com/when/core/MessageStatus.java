package com.when.core;

/** Lifecycle state of a delayed message. */
public enum MessageStatus {
    PENDING,
    DELIVERING,
    DELIVERED,
    FAILED,
    CANCELLED;

    /** Returns whether the common state-machine contract permits the transition. */
    public boolean canTransitionTo(MessageStatus target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case PENDING -> target == DELIVERING || target == CANCELLED;
            case DELIVERING -> target == DELIVERED || target == PENDING || target == FAILED;
            case DELIVERED, FAILED, CANCELLED -> false;
        };
    }
}
