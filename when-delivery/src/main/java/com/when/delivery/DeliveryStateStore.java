package com.when.delivery;

import com.when.core.DeliveryResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence boundary for delivery leases and the bounded recent-attempt audit trail. */
public interface DeliveryStateStore extends AutoCloseable {
    Optional<DeliveryLease> tryAcquire(String messageId, String nodeId, Instant leaseUntil);

    Optional<DeliveryLease> currentLease(String messageId);

    void complete(String messageId, String attemptId, DeliveryResult result);

    List<String> findExpiredLeases(Instant now, int limit);

    void appendAttempt(DeliveryAttempt attempt);

    List<DeliveryAttempt> recentAttempts(String messageId, int limit);

    @Override
    default void close() {
    }
}
