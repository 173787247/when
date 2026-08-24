package com.when.observability;

/** Metric names are centralized so business modules cannot create arbitrary series. */
public final class WhenMetrics {
    public static final String MESSAGES_SUBMITTED = "when_messages_submitted_total";
    public static final String SINK_DELIVERIES = "when_sink_deliveries_total";
    public static final String MESSAGES_IN_STATE = "when_messages_in_state";
    public static final String DELIVERY_LAG = "when_delivery_lag_seconds";
    public static final String SINK_DELIVERY_DURATION = "when_sink_delivery_duration_seconds";
    public static final String MASTER_FAILOVER = "when_master_failover_total";
    public static final String CONTROLLER_ELECTIONS = "when_controller_elections_total";
    public static final String REPLICA_SYNC_QUEUE_SIZE = "when_replica_sync_queue_size";
    public static final String REPLICA_REBUILD = "when_replica_rebuild_total";
    public static final String REDIS_OPERATIONS = "when_redis_operations_total";
    public static final String ETCD_OPERATIONS = "when_etcd_operations_total";
    public static final String REDIS_OPERATION_DURATION = "when_redis_operation_duration_seconds";
    public static final String ETCD_OPERATION_DURATION = "when_etcd_operation_duration_seconds";
    public static final String TRACE_EXPORT_FAILURES = "when_trace_export_failures_total";
    public static final String DUE_HANDOFF_REJECTED = "when_due_handoff_rejected_total";

    private WhenMetrics() {
    }
}
