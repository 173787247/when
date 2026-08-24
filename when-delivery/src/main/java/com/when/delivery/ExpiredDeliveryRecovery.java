package com.when.delivery;

import com.when.core.DeliveryResult;
import com.when.core.Message;
import com.when.core.MessageStatus;
import com.when.core.StatePatch;
import com.when.core.StoragePlugin;
import com.when.core.TimeWheelRegistry;
import java.time.Clock;
import java.util.Objects;

/** Bounded recovery pass for PENDING/DELIVERING messages whose delivery lease expired. */
public final class ExpiredDeliveryRecovery {
    private final StoragePlugin storage;
    private final DeliveryStateStore deliveryState;
    private final TimeWheelRegistry timeWheels;
    private final Clock clock;
    private final int batchSize;

    public ExpiredDeliveryRecovery(
            StoragePlugin storage,
            DeliveryStateStore deliveryState,
            TimeWheelRegistry timeWheels,
            Clock clock,
            int batchSize) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.deliveryState = Objects.requireNonNull(deliveryState, "deliveryState");
        this.timeWheels = Objects.requireNonNull(timeWheels, "timeWheels");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
    }

    public int recoverOnce() {
        int recovered = 0;
        for (String messageId : deliveryState.findExpiredLeases(clock.instant(), batchSize)) {
            DeliveryLease lease = deliveryState.currentLease(messageId).orElse(null);
            if (lease == null || lease.leaseUntil().isAfter(clock.instant())) {
                continue;
            }
            Message message = storage.get(messageId).orElse(null);
            if (message == null || isTerminal(message.status())) {
                deliveryState.complete(messageId, lease.attemptId(), expiredResult());
                continue;
            }
            if (message.status() == MessageStatus.DELIVERING) {
                storage.transition(
                        messageId,
                        MessageStatus.DELIVERING,
                        MessageStatus.PENDING,
                        new StatePatch(
                                message.retryCount(),
                                clock.millis(),
                                null,
                                "DELIVERY_LEASE_EXPIRED"));
                message = storage.get(messageId).orElse(null);
            }
            if (message != null && message.status() == MessageStatus.PENDING) {
                timeWheels.require(message.timeWheelId()).add(message);
                deliveryState.complete(messageId, lease.attemptId(), expiredResult());
                recovered++;
            }
        }
        return recovered;
    }

    private static DeliveryResult expiredResult() {
        return DeliveryResult.retryableFailure(
                "DELIVERY_LEASE_EXPIRED", "delivery lease expired before completion", 0);
    }

    private static boolean isTerminal(MessageStatus status) {
        return status == MessageStatus.DELIVERED
                || status == MessageStatus.FAILED
                || status == MessageStatus.CANCELLED;
    }
}
