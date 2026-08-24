package com.when.observability;

/** Stable event values intended for Loki queries and incident automation. */
public enum LogEvent {
    MESSAGE_SUBMITTED("message_submitted"),
    MESSAGE_DELIVERED("message_delivered"),
    MESSAGE_DELIVERY_FAILED("message_delivery_failed"),
    MESSAGE_CANCELLED("message_cancelled"),
    CONTROLLER_ELECTION("controller_election"),
    MASTER_FAILOVER("master_failover"),
    REPLICA_REBUILD("replica_rebuild"),
    DEPENDENCY_OPERATION("dependency_operation"),
    NODE_READINESS_CHANGED("node_readiness_changed"),
    TRACE_EXPORT_FAILED("trace_export_failed"),
    SERVER_LIFECYCLE("server_lifecycle");

    private final String value;

    LogEvent(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
