package com.when.cluster.etcd;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonAlias;

/** Persistent Master/Slave placement stored below {@code /when/timewheels/}. */
public record TimeWheelMetadata(
        String master,
        String slave,
        String status,
        @JsonProperty("sync_state") String syncState,
        @JsonAlias("assignment_version") long epoch) {

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

    /** Lesson 47 name for the fencing value; {@code epoch} remains the persisted field. */
    @JsonIgnore
    public long assignmentVersion() {
        return epoch;
    }

    public TimeWheelMetadata withSyncState(String newSyncState) {
        return new TimeWheelMetadata(master, slave, status, newSyncState, epoch);
    }
}
