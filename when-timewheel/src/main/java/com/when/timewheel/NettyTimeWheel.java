package com.when.timewheel;

import com.when.core.DueMessageHandler;
import com.when.core.Message;
import com.when.core.TimeWheel;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One isolated logical time wheel backed by a dedicated {@link NettyTimerBackend} and bounded due
 * handoff executor.
 *
 * <p>The timer callback only performs an identity check, removes the in-memory handle, and enqueues
 * the message id. Persistence, state transitions, network calls, and sink work belong to the
 * injected {@link DueMessageHandler} running on the due executor.
 */
public final class NettyTimeWheel implements TimeWheel, AutoCloseable {
    private enum Lifecycle {
        NEW,
        RUNNING,
        STOPPED
    }

    private final String id;
    private final DueMessageHandler dueHandler;
    private final Clock clock;
    private final TimerBackend timerBackend;
    private final ThreadPoolExecutor dueExecutor;
    private final long maxPendingTimeouts;
    private final Object mutationLock = new Object();
    private final Map<String, ScheduledEntry> handles = new HashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong rejectedDueHandoffs = new AtomicLong();
    private final AtomicLong dueHandlerFailures = new AtomicLong();
    private Lifecycle lifecycle = Lifecycle.NEW;

    public NettyTimeWheel(String id, DueMessageHandler dueHandler) {
        this(id, dueHandler, Clock.systemUTC(), TimeWheelConfig.defaults());
    }

    public NettyTimeWheel(
            String id, DueMessageHandler dueHandler, Clock clock, TimeWheelConfig config) {
        this(
                requireId(id),
                dueHandler,
                clock,
                new NettyTimerBackend(id, config),
                newDueExecutor(id, config),
                config.maxPendingTimeouts());
    }

    NettyTimeWheel(
            String id,
            DueMessageHandler dueHandler,
            Clock clock,
            TimerBackend timerBackend,
            ThreadPoolExecutor dueExecutor,
            long maxPendingTimeouts) {
        this.id = requireId(id);
        this.dueHandler = Objects.requireNonNull(dueHandler, "dueHandler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timerBackend = Objects.requireNonNull(timerBackend, "timerBackend");
        this.dueExecutor = Objects.requireNonNull(dueExecutor, "dueExecutor");
        if (maxPendingTimeouts <= 0) {
            throw new IllegalArgumentException("maxPendingTimeouts must be positive");
        }
        this.maxPendingTimeouts = maxPendingTimeouts;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public void add(Message message) {
        Objects.requireNonNull(message, "message");
        String messageId = requireMessageId(message.messageId());
        if (!id.equals(message.timeWheelId())) {
            throw new IllegalArgumentException("message belongs to a different time wheel");
        }

        long delayMillis = delayMillis(message.nextAttemptAt(), clock.millis());
        synchronized (mutationLock) {
            ensureNotStopped();
            ScheduledEntry old = handles.get(messageId);
            if (old == null && handles.size() >= maxPendingTimeouts) {
                throw new RejectedExecutionException("time wheel pending capacity reached");
            }

            ScheduledEntry entry = new ScheduledEntry(messageId, generation.incrementAndGet());
            handles.put(messageId, entry);
            if (old != null) {
                old.cancel();
            }

            try {
                TimerBackend.ScheduledTask task =
                        timerBackend.schedule(() -> onTimeout(entry), delayMillis);
                entry.attach(task);
            } catch (RuntimeException exception) {
                handles.remove(messageId, entry);
                entry.cancel();
                throw exception;
            }
        }
    }

    @Override
    public void remove(String messageId) {
        String requiredId = requireMessageId(messageId);
        ScheduledEntry entry;
        synchronized (mutationLock) {
            entry = handles.remove(requiredId);
        }
        if (entry != null) {
            entry.cancel();
        }
    }

    @Override
    public void start() {
        synchronized (mutationLock) {
            ensureNotStopped();
            if (lifecycle == Lifecycle.RUNNING) {
                return;
            }
            timerBackend.start();
            lifecycle = Lifecycle.RUNNING;
        }
    }

    @Override
    public void stop() {
        List<ScheduledEntry> entries;
        synchronized (mutationLock) {
            if (lifecycle == Lifecycle.STOPPED) {
                return;
            }
            lifecycle = Lifecycle.STOPPED;
            entries = new ArrayList<>(handles.values());
            handles.clear();
        }
        entries.forEach(ScheduledEntry::cancel);
        timerBackend.stop();
        dueExecutor.shutdownNow();
    }

    @Override
    public void close() {
        stop();
    }

    /** Current number of indexed messages; exposed for bounded-resource monitoring. */
    public int pendingCount() {
        synchronized (mutationLock) {
            return handles.size();
        }
    }

    /** Number of due handoffs rejected because this wheel's bounded queue was full or stopped. */
    public long rejectedDueHandoffCount() {
        return rejectedDueHandoffs.get();
    }

    /** Number of runtime failures contained at the asynchronous handler boundary. */
    public long dueHandlerFailureCount() {
        return dueHandlerFailures.get();
    }

    public boolean isStarted() {
        synchronized (mutationLock) {
            return lifecycle == Lifecycle.RUNNING;
        }
    }

    long generationOf(String messageId) {
        synchronized (mutationLock) {
            ScheduledEntry entry = handles.get(messageId);
            return entry == null ? -1L : entry.generation;
        }
    }

    private void onTimeout(ScheduledEntry entry) {
        synchronized (mutationLock) {
            if (!handles.remove(entry.messageId, entry) || lifecycle == Lifecycle.STOPPED) {
                return;
            }
        }
        try {
            dueExecutor.execute(() -> invokeDueHandler(entry.messageId));
        } catch (RejectedExecutionException exception) {
            // Redis retains the PENDING message fact, so a later rebuild can safely recover it.
            rejectedDueHandoffs.incrementAndGet();
        }
    }

    private void invokeDueHandler(String messageId) {
        try {
            dueHandler.onDue(messageId);
        } catch (RuntimeException exception) {
            dueHandlerFailures.incrementAndGet();
        }
    }

    private void ensureNotStopped() {
        if (lifecycle == Lifecycle.STOPPED) {
            throw new IllegalStateException("time wheel is stopped");
        }
    }

    private static long delayMillis(long targetMillis, long nowMillis) {
        if (targetMillis <= nowMillis) {
            return 0L;
        }
        try {
            return Math.subtractExact(targetMillis, nowMillis);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static ThreadPoolExecutor newDueExecutor(String id, TimeWheelConfig config) {
        String safeId = requireId(id).replaceAll("[^A-Za-z0-9_.-]", "_");
        return new ThreadPoolExecutor(
                config.dueThreads(),
                config.dueThreads(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.dueQueueCapacity()),
                new NamedThreadFactory("when-tw-" + safeId + "-due"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static String requireId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("timeWheelId must not be blank");
        }
        return value;
    }

    private static String requireMessageId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        return value;
    }

    private static final class ScheduledEntry {
        private final String messageId;
        private final long generation;
        private TimerBackend.ScheduledTask task;
        private boolean cancellationRequested;

        private ScheduledEntry(String messageId, long generation) {
            this.messageId = messageId;
            this.generation = generation;
        }

        synchronized void attach(TimerBackend.ScheduledTask task) {
            this.task = Objects.requireNonNull(task, "task");
            if (cancellationRequested) {
                task.cancel();
            }
        }

        synchronized void cancel() {
            cancellationRequested = true;
            if (task != null) {
                task.cancel();
            }
        }
    }
}
