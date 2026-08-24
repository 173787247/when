package com.when.cluster.etcd;

public record EtcdWatchEvent(Type type, String key, String value, long revision) {
    public enum Type {
        PUT,
        DELETE
    }
}
