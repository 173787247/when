package com.when.cluster.etcd;

/** Non-sensitive failure raised by the metadata client boundary. */
public final class EtcdClientException extends RuntimeException {
    public EtcdClientException(String operation, Throwable cause) {
        super("etcd operation failed: " + operation, cause);
    }
}
