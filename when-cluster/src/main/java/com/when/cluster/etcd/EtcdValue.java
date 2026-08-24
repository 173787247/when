package com.when.cluster.etcd;

/** A metadata value together with the etcd revisions needed for fenced decisions. */
public record EtcdValue(String value, long createRevision, long modRevision) {
    public EtcdValue {
        if (value == null) {
            throw new NullPointerException("value");
        }
        if (createRevision < 0 || modRevision < 0) {
            throw new IllegalArgumentException("etcd revisions must not be negative");
        }
    }
}
