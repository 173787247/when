package com.when.observability;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Thread-safe readiness state. Liveness intentionally has no dependency checks. */
public final class ReadinessManager {
    private final AtomicBoolean initialized = new AtomicBoolean();
    private final AtomicBoolean redisAvailable = new AtomicBoolean();
    private final AtomicBoolean etcdAvailable = new AtomicBoolean();
    private final AtomicBoolean registered = new AtomicBoolean();
    private final AtomicBoolean rolesRecovered = new AtomicBoolean();

    public void initialized(boolean value) {
        initialized.set(value);
    }

    public void redisAvailable(boolean value) {
        redisAvailable.set(value);
    }

    public void etcdAvailable(boolean value) {
        etcdAvailable.set(value);
    }

    public void registered(boolean value) {
        registered.set(value);
    }

    /** True is also correct for a healthy forwarding-only node with no local role assignment. */
    public void rolesRecovered(boolean value) {
        rolesRecovered.set(value);
    }

    public ReadinessSnapshot snapshot() {
        List<ReadinessReason> reasons = new ArrayList<>();
        if (!initialized.get()) {
            reasons.add(ReadinessReason.INITIALIZING);
        }
        if (!etcdAvailable.get()) {
            reasons.add(ReadinessReason.ETCD_UNAVAILABLE);
        }
        if (!redisAvailable.get()) {
            reasons.add(ReadinessReason.REDIS_UNAVAILABLE);
        }
        if (!registered.get()) {
            reasons.add(ReadinessReason.NODE_NOT_REGISTERED);
        }
        if (!rolesRecovered.get()) {
            reasons.add(ReadinessReason.ROLE_RECOVERING);
        }
        return new ReadinessSnapshot(reasons.isEmpty(), reasons);
    }
}
