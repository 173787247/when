package com.when.cluster.etcd;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Small, non-sensitive value stored below {@code /when/nodes/}. */
public record NodeMetadata(
        String ip,
        int port,
        @JsonProperty("start_time") long startTime,
        double load) {

    public NodeMetadata {
        if (ip == null || ip.isBlank()) {
            throw new IllegalArgumentException("ip must not be blank");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        if (startTime < 0) {
            throw new IllegalArgumentException("startTime must not be negative");
        }
        if (!Double.isFinite(load) || load < 0) {
            throw new IllegalArgumentException("load must be a finite non-negative number");
        }
    }
}
