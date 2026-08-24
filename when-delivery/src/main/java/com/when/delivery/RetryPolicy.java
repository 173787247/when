package com.when.delivery;

import com.when.core.DeliveryResult;
import java.time.Duration;

/** Uniform retry decision and backoff contract. */
public interface RetryPolicy {
    boolean shouldRetry(DeliveryResult result, int retryCount);

    Duration nextDelay(int nextRetryNumber);
}
