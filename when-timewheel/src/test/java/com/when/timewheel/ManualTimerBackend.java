package com.when.timewheel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

/** Deterministic test backend that represents long delays without wall-clock waiting. */
final class ManualTimerBackend implements TimerBackend {
    private final List<ManualTask> tasks = new ArrayList<>();
    private final int capacity;
    private long nowMillis;
    private long sequence;
    private boolean started;
    private boolean stopped;

    ManualTimerBackend() {
        this(Integer.MAX_VALUE);
    }

    ManualTimerBackend(int capacity) {
        this.capacity = capacity;
    }

    @Override
    public synchronized void start() {
        if (stopped) {
            throw new IllegalStateException("stopped");
        }
        started = true;
    }

    @Override
    public synchronized ScheduledTask schedule(Runnable task, long delayMillis) {
        if (stopped) {
            throw new IllegalStateException("stopped");
        }
        long active = tasks.stream().filter(item -> !item.cancelled && !item.fired).count();
        if (active >= capacity) {
            throw new RejectedExecutionException("manual timer capacity reached");
        }
        ManualTask manualTask = new ManualTask(nowMillis + delayMillis, sequence++, task);
        tasks.add(manualTask);
        return manualTask;
    }

    @Override
    public synchronized void stop() {
        stopped = true;
        tasks.forEach(item -> item.cancelled = true);
    }

    synchronized long lastDelayMillis() {
        ManualTask task = tasks.get(tasks.size() - 1);
        return task.dueMillis - nowMillis;
    }

    synchronized int scheduleCount() {
        return tasks.size();
    }

    synchronized boolean isStarted() {
        return started;
    }

    void advanceBy(long millis) {
        runDue(millis, false);
    }

    void advanceByIncludingCancelled(long millis) {
        runDue(millis, true);
    }

    private void runDue(long millis, boolean includeCancelled) {
        List<ManualTask> due;
        synchronized (this) {
            nowMillis += millis;
            if (!started || stopped) {
                return;
            }
            due =
                    tasks.stream()
                            .filter(item -> !item.fired && item.dueMillis <= nowMillis)
                            .filter(item -> includeCancelled || !item.cancelled)
                            .sorted(
                                    Comparator.comparingLong((ManualTask item) -> item.dueMillis)
                                            .thenComparingLong(item -> item.sequence))
                            .toList();
            due.forEach(item -> item.fired = true);
        }
        due.forEach(item -> item.runnable.run());
    }

    private static final class ManualTask implements ScheduledTask {
        private final long dueMillis;
        private final long sequence;
        private final Runnable runnable;
        private boolean cancelled;
        private boolean fired;

        private ManualTask(long dueMillis, long sequence, Runnable runnable) {
            this.dueMillis = dueMillis;
            this.sequence = sequence;
            this.runnable = runnable;
        }

        @Override
        public synchronized boolean cancel() {
            if (cancelled || fired) {
                return false;
            }
            cancelled = true;
            return true;
        }
    }
}
