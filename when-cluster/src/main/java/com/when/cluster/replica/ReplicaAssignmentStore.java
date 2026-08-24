package com.when.cluster.replica;

import com.when.core.TimeWheelAssignment;
import java.util.Optional;

/** Read/conditional-state boundary for Controller-owned time-wheel metadata. */
public interface ReplicaAssignmentStore {
    Optional<TimeWheelAssignment> current(String twId);

    boolean markSyncState(String twId, long assignmentVersion, String syncState);
}
