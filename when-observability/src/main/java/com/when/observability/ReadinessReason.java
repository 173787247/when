package com.when.observability;

/** Fixed reason codes returned by /ready. */
public enum ReadinessReason {
    INITIALIZING,
    ETCD_UNAVAILABLE,
    REDIS_UNAVAILABLE,
    NODE_NOT_REGISTERED,
    ROLE_RECOVERING
}
