package com.when.cluster.etcd;

import java.util.Map;

/** A linearizable prefix snapshot read at one etcd revision. */
public record EtcdSnapshot(long revision, Map<String, EtcdValue> values) {
    public EtcdSnapshot {
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        values = Map.copyOf(values);
    }
}
