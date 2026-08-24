package com.when.cluster.controller;

/** Public result of Controller time-wheel creation without exposing mutable store internals. */
public record TimeWheelAssignmentView(
        String twId,
        String master,
        String slave,
        String status,
        String syncState,
        long assignmentVersion) {
    public TimeWheelAssignmentView {
        twId = Text.segment(twId, "twId");
        master = Text.segment(master, "master");
        slave = Text.segment(slave, "slave");
        if (master.equals(slave)) {
            throw new IllegalArgumentException("Master and Slave must be on different nodes");
        }
        status = Text.require(status, "status");
        syncState = Text.require(syncState, "syncState");
        if (assignmentVersion < 0) {
            throw new IllegalArgumentException("assignmentVersion must not be negative");
        }
    }
}
