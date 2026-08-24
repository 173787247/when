package com.when.timewheel;

/**
 * Thin scheduling boundary used to keep Netty types inside this module and make long-delay tests
 * deterministic.
 */
public interface TimerBackend {
    /** Starts the scheduling worker. Implementations must make this operation idempotent. */
    void start();

    /** Registers a one-shot task after the supplied non-negative delay. */
    ScheduledTask schedule(Runnable task, long delayMillis);

    /** Stops the worker and returns without executing newly due tasks. */
    void stop();

    /** Handle for constant-time best-effort cancellation. */
    @FunctionalInterface
    interface ScheduledTask {
        boolean cancel();
    }
}
