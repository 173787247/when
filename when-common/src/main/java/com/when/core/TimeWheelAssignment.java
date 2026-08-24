package com.when.core;

/** Controller-owned placement fact consumed by replica and failover executors. */
public record TimeWheelAssignment(
        String twId,
        String master,
        String slave,
        String status,
        String syncState,
        long assignmentVersion) {

    public TimeWheelAssignment {
        twId = requireText(twId, "twId");
        master = requireText(master, "master");
        slave = requireText(slave, "slave");
        status = requireText(status, "status");
        syncState = requireText(syncState, "syncState");
        if (master.equals(slave)) {
            throw new IllegalArgumentException("master and slave must be on different nodes");
        }
        if (assignmentVersion < 0) {
            throw new IllegalArgumentException("assignmentVersion must not be negative");
        }
    }

    public boolean isMaster(String nodeId) {
        return master.equals(nodeId);
    }

    public boolean isSlave(String nodeId) {
        return slave.equals(nodeId);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
