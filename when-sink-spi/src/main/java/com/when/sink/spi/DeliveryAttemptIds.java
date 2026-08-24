package com.when.sink.spi;

import java.util.UUID;

/** Generates opaque per-attempt identifiers without embedding node or target data. */
public final class DeliveryAttemptIds {
    private DeliveryAttemptIds() {
    }

    public static String next() {
        return UUID.randomUUID().toString();
    }
}
