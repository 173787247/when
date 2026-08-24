package com.when.cluster.etcd;

/** Result of a conditional metadata transaction. */
public record EtcdTxnResult(boolean committed, long revision) {
    public EtcdTxnResult {
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
    }
}
