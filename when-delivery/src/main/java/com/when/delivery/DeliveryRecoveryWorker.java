package com.when.delivery;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Isolated background worker that performs bounded expired-lease recovery passes. */
public final class DeliveryRecoveryWorker implements AutoCloseable {
    private final ExpiredDeliveryRecovery recovery;
    private final Duration interval;
    private final ScheduledExecutorService executor;
    private final AtomicLong failures = new AtomicLong();
    private boolean started;

    public DeliveryRecoveryWorker(ExpiredDeliveryRecovery recovery, Duration interval) {
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "when-delivery-recovery");
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        executor.scheduleWithFixedDelay(
                this::recoverSafely,
                interval.toMillis(),
                interval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    public long failureCount() {
        return failures.get();
    }

    @Override
    public synchronized void close() {
        executor.shutdownNow();
    }

    private void recoverSafely() {
        try {
            recovery.recoverOnce();
        } catch (RuntimeException exception) {
            failures.incrementAndGet();
        }
    }
}
