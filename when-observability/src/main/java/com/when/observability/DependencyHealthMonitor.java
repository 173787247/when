package com.when.observability;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Bounded background dependency probes used only for readiness, never liveness. */
public final class DependencyHealthMonitor implements AutoCloseable {
    private final ReadinessManager readiness;
    private final BooleanSupplier redis;
    private final BooleanSupplier etcd;
    private final Duration interval;
    private final ScheduledExecutorService executor;

    public DependencyHealthMonitor(
            ReadinessManager readiness,
            BooleanSupplier redis,
            BooleanSupplier etcd,
            Duration interval) {
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.redis = Objects.requireNonNull(redis, "redis");
        this.etcd = Objects.requireNonNull(etcd, "etcd");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("health-check interval must be positive");
        }
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "when-readiness-probe");
            thread.setDaemon(true);
            return thread;
        });
    }

    public DependencyHealthMonitor start() {
        probe();
        executor.scheduleWithFixedDelay(
                this::probe, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        return this;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private void probe() {
        readiness.redisAvailable(safe(redis));
        readiness.etcdAvailable(safe(etcd));
    }

    private static boolean safe(BooleanSupplier probe) {
        try {
            return probe.getAsBoolean();
        } catch (RuntimeException failure) {
            return false;
        }
    }
}
