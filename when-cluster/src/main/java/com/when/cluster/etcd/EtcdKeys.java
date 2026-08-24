package com.when.cluster.etcd;

import java.util.Objects;

/** The single source of truth for When's etcd key space. */
public final class EtcdKeys {
    public static final String ROOT_PREFIX = "/when/";
    public static final String NODES_PREFIX = "/when/nodes/";
    public static final String WORKERS_PREFIX = "/when/workers/";
    public static final String CONTROLLER = "/when/controller";
    public static final String CONTROLLER_PROGRESS = "/when/controller/progress";
    public static final String TIME_WHEELS_PREFIX = "/when/timewheels/";
    public static final String CONFIG_PREFIX = "/when/config/";

    private EtcdKeys() {
    }

    public static String node(String nodeId) {
        return NODES_PREFIX + segment(nodeId, "nodeId");
    }

    public static String worker(int workerId) {
        if (workerId < 0 || workerId > 1023) {
            throw new IllegalArgumentException("workerId must be between 0 and 1023");
        }
        return WORKERS_PREFIX + workerId;
    }

    public static String timeWheel(String timeWheelId) {
        return TIME_WHEELS_PREFIX + segment(timeWheelId, "timeWheelId");
    }

    public static String config(String name) {
        return CONFIG_PREFIX + segment(name, "name");
    }

    private static String segment(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.indexOf('/') >= 0) {
            throw new IllegalArgumentException(field + " must be a non-blank key segment");
        }
        return value;
    }
}
