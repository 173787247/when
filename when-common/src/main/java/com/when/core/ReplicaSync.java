package com.when.core;

import java.util.concurrent.CompletionStage;

/** Asynchronous Master-to-Slave synchronization and Redis rebuild contract. */
public interface ReplicaSync {
    CompletionStage<SyncAck> sync(ReplicaOperation operation);

    void markOutOfSync(String twId, long assignmentVersion, String reason);

    CompletionStage<RebuildResult> rebuildFromRedis(
            String twId, long assignmentVersion, long startSequence);
}
