package com.when.delivery;

import com.when.core.DeliveryResult;
import java.time.Duration;
import java.util.Objects;

/** Default 30s, 1m, 2m, 4m, 8m retry policy with a configurable 30-minute ceiling. */
public final class ExponentialRetryPolicy implements RetryPolicy {
    public static final int DEFAULT_MAX_RETRIES = 5;
    private final int maxRetries;
    private final Duration initialDelay;
    private final Duration maximumDelay;

    public ExponentialRetryPolicy() {
        this(DEFAULT_MAX_RETRIES, Duration.ofSeconds(30), Duration.ofMinutes(30));
    }

    public ExponentialRetryPolicy(int maxRetries, Duration initialDelay, Duration maximumDelay) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative");
        }
        this.maxRetries = maxRetries;
        this.initialDelay = requirePositive(initialDelay, "initialDelay");
        this.maximumDelay = requirePositive(maximumDelay, "maximumDelay");
    }

    @Override
    public boolean shouldRetry(DeliveryResult result, int retryCount) {
        Objects.requireNonNull(result, "result");
        return !result.success() && result.retryable() && retryCount < maxRetries;
    }

    @Override
    public Duration nextDelay(int nextRetryNumber) {
        if (nextRetryNumber < 1 || nextRetryNumber > maxRetries) {
            throw new IllegalArgumentException("nextRetryNumber is outside the configured retry range");
        }
        Duration delay = initialDelay;
        for (int number = 1; number < nextRetryNumber; number++) {
            if (delay.compareTo(maximumDelay.dividedBy(2)) > 0) {
                return maximumDelay;
            }
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(maximumDelay) > 0 ? maximumDelay : delay;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
