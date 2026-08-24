package com.when.cluster.etcd;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Persistent Master/Slave placement stored below {@code /when/timewheels/}. */
public record TimeWheelMetadata(
        String master,
        String slave,
        String status,
        @JsonProperty("sync_state") String syncState,
        long epoch) {

    public TimeWheelMetadata(String master, String slave, String status) {
        this(master, slave, status, "in_sync", 0);
    }

    public TimeWheelMetadata {
        if (master == null || master.isBlank()) {
            throw new IllegalArgumentException("master must not be blank");
        }
        if (slave == null || slave.isBlank()) {
            throw new IllegalArgumentException("slave must not be blank");
        }
        if (master.equals(slave)) {
            throw new IllegalArgumentException("master and slave must be on different nodes");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
        if (syncState == null || syncState.isBlank()) {
            throw new IllegalArgumentException("syncState must not be blank");
        }
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch must not be negative");
        }
    }
}
