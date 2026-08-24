package com.when.cluster.controller;

/** Current lesson only needs a stable time-wheel identity for creation. */
public record TimeWheelSpec(String twId) {
    public TimeWheelSpec {
        twId = Text.segment(twId, "twId");
    }
}
