package com.when.timewheel;

import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Production {@link TimerBackend} backed by one dedicated Netty hashed-wheel timer. */
public final class NettyTimerBackend implements TimerBackend {
    private enum Lifecycle {
        NEW,
        STARTED,
        STOPPED
    }

    private final HashedWheelTimer timer;
    private final List<PendingTask> pendingBeforeStart = new ArrayList<>();
    private Lifecycle lifecycle = Lifecycle.NEW;

    public NettyTimerBackend(String timeWheelId, TimeWheelConfig config) {
        Objects.requireNonNull(config, "config");
        String safeId = safeThreadComponent(timeWheelId);
        this.timer =
                new HashedWheelTimer(
                        new NamedThreadFactory("when-tw-" + safeId + "-timer"),
                        config.tickMillis(),
                        TimeUnit.MILLISECONDS,
                        config.wheelSize(),
                        false,
                        config.maxPendingTimeouts());
    }

    @Override
    public synchronized void start() {
        if (lifecycle == Lifecycle.STOPPED) {
            throw new IllegalStateException("timer backend is stopped");
        }
        if (lifecycle == Lifecycle.STARTED) {
            return;
        }
        timer.start();
        lifecycle = Lifecycle.STARTED;
        pendingBeforeStart.forEach(PendingTask::activate);
        pendingBeforeStart.clear();
    }

    @Override
    public synchronized ScheduledTask schedule(Runnable task, long delayMillis) {
        Objects.requireNonNull(task, "task");
        if (delayMillis < 0) {
            throw new IllegalArgumentException("delayMillis must not be negative");
        }
        if (lifecycle == Lifecycle.STOPPED) {
            throw new IllegalStateException("timer backend is stopped");
        }
        PendingTask pendingTask = new PendingTask(task, deadlineNanos(delayMillis));
        if (lifecycle == Lifecycle.NEW) {
            pendingBeforeStart.add(pendingTask);
        } else {
            pendingTask.activate();
        }
        return pendingTask;
    }

    @Override
    public synchronized void stop() {
        if (lifecycle == Lifecycle.STOPPED) {
            return;
        }
        lifecycle = Lifecycle.STOPPED;
        pendingBeforeStart.forEach(PendingTask::cancel);
        pendingBeforeStart.clear();
        timer.stop();
    }

    private static String safeThreadComponent(String timeWheelId) {
        if (timeWheelId == null || timeWheelId.isBlank()) {
            throw new IllegalArgumentException("timeWheelId must not be blank");
        }
        return timeWheelId.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static long deadlineNanos(long delayMillis) {
        long delayNanos = TimeUnit.MILLISECONDS.toNanos(delayMillis);
        long now = System.nanoTime();
        long deadline = now + delayNanos;
        return deadline < 0L && now > 0L ? Long.MAX_VALUE : deadline;
    }

    private final class PendingTask implements ScheduledTask {
        private final Runnable runnable;
        private final long deadlineNanos;
        private Timeout timeout;
        private boolean cancelled;

        private PendingTask(Runnable runnable, long deadlineNanos) {
            this.runnable = runnable;
            this.deadlineNanos = deadlineNanos;
        }

        private synchronized void activate() {
            if (cancelled) {
                return;
            }
            long remainingNanos = Math.max(0L, deadlineNanos - System.nanoTime());
            timeout =
                    timer.newTimeout(
                            ignored -> runnable.run(), remainingNanos, TimeUnit.NANOSECONDS);
            if (cancelled) {
                timeout.cancel();
            }
        }

        @Override
        public synchronized boolean cancel() {
            if (cancelled) {
                return false;
            }
            cancelled = true;
            return timeout == null || timeout.cancel();
        }
    }
}
