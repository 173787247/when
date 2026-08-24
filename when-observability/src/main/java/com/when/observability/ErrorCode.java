package com.when.observability;

/** Bounded error-code vocabulary; sensitive exception messages are deliberately excluded. */
public enum ErrorCode {
    NONE,
    VALIDATION_FAILED,
    REDIS_UNAVAILABLE,
    ETCD_UNAVAILABLE,
    ROLE_RECOVERING,
    NODE_NOT_REGISTERED,
    INITIALIZING,
    SINK_FAILURE,
    EXPORT_FAILED,
    INTERNAL_ERROR
}
