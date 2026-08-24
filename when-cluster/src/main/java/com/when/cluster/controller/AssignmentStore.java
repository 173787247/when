package com.when.cluster.controller;

import java.util.function.Consumer;

/** ETCD-backed authority for Controller snapshots, watches and assignment commits. */
public interface AssignmentStore {
    ClusterState loadSnapshot(long observedAt);

    CommitResult compareAndSet(AssignmentDecision decision);

    /** Persists event replay progress under the same Controller-term fence. */
    default boolean recordProgress(ControllerTerm term, ClusterEvent event) {
        return true;
    }

    WatchHandle watchFrom(
            long revision,
            Consumer<ClusterEvent> eventHandler,
            Runnable snapshotRequired);

    @FunctionalInterface
    interface WatchHandle extends AutoCloseable {
        @Override
        void close();
    }
}
