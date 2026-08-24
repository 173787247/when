package com.when.cluster.etcd;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Persistent Master/Slave placement stored below {@code /when/timewheels/}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TimeWheelMetadata(
        String master,
        String slave,
        String status,
        @JsonProperty("sync_state") String syncState,
        @JsonProperty("epoch") @JsonAlias("assignment_version") long assignmentVersion,
        @JsonProperty("decision_id") String decisionId,
        @JsonProperty("candidate_slave") String candidateSlave,
        @JsonProperty("candidate_state") String candidateState,
        @JsonProperty("last_moved_at") @JsonInclude(JsonInclude.Include.NON_DEFAULT)
                long lastMovedAt) {

    public TimeWheelMetadata(String master, String slave, String status) {
        this(master, slave, status, "in_sync", 0, null, null, null, 0);
    }

    public TimeWheelMetadata(
            String master,
            String slave,
            String status,
            String syncState,
            long assignmentVersion) {
        this(master, slave, status, syncState, assignmentVersion, null, null, null, 0);
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
        if (assignmentVersion < 0) {
            throw new IllegalArgumentException("assignmentVersion must not be negative");
        }
        if (lastMovedAt < 0) {
            throw new IllegalArgumentException("lastMovedAt must not be negative");
        }
        decisionId = optionalText(decisionId, "decisionId");
        candidateSlave = optionalText(candidateSlave, "candidateSlave");
        candidateState = optionalText(candidateState, "candidateState");
        if ((candidateSlave == null) != (candidateState == null)) {
            throw new IllegalArgumentException(
                    "candidateSlave and candidateState must either both be present or both be absent");
        }
        if (candidateSlave != null
                && (candidateSlave.equals(master) || candidateSlave.equals(slave))) {
            throw new IllegalArgumentException("candidate Slave must be on a third node");
        }
    }

    /** Lesson 47 compatibility name for the assignment fencing value. */
    @JsonIgnore
    public long epoch() {
        return assignmentVersion;
    }

    public TimeWheelMetadata withSyncState(String newSyncState) {
        return new TimeWheelMetadata(
                master,
                slave,
                status,
                newSyncState,
                assignmentVersion,
                decisionId,
                candidateSlave,
                candidateState,
                lastMovedAt);
    }

    public TimeWheelMetadata withCandidate(
            String newDecisionId,
            String newCandidateSlave,
            String newCandidateState,
            long newAssignmentVersion) {
        return new TimeWheelMetadata(
                master,
                slave,
                status,
                syncState,
                newAssignmentVersion,
                newDecisionId,
                newCandidateSlave,
                newCandidateState,
                lastMovedAt);
    }

    private static String optionalText(String value, String field) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank when present");
        }
        return value;
    }
}
