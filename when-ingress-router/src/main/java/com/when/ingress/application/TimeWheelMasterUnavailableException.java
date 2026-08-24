package com.when.ingress.application;

/** Indicates that the authoritative cluster view has no current Master for a time wheel. */
public final class TimeWheelMasterUnavailableException extends IllegalStateException {
    public TimeWheelMasterUnavailableException(String timeWheelId) {
        super("no Master is available for time wheel " + timeWheelId);
    }
}
