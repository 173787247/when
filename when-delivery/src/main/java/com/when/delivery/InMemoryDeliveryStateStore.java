package com.when.delivery;

import com.when.core.DeliveryResult;
import com.when.sink.spi.DeliveryAttemptIds;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Small deterministic implementation useful for composition tests and embedded use. */
public final class InMemoryDeliveryStateStore implements DeliveryStateStore {
    private static final int MAX_ATTEMPTS = 20;
    private final Map<String, DeliveryLease> leases = new HashMap<>();
    private final Map<String, Deque<DeliveryAttempt>> attempts = new HashMap<>();

    @Override
    public synchronized Optional<DeliveryLease> tryAcquire(
            String messageId, String nodeId, Instant leaseUntil) {
        Instant now = Instant.now();
        DeliveryLease current = leases.get(messageId);
        if (current != null && current.leaseUntil().isAfter(now)) {
            return Optional.empty();
        }
        DeliveryLease acquired = new DeliveryLease(
                messageId, nodeId, DeliveryAttemptIds.next(), leaseUntil);
        leases.put(messageId, acquired);
        return Optional.of(acquired);
    }

    @Override
    public synchronized Optional<DeliveryLease> currentLease(String messageId) {
        return Optional.ofNullable(leases.get(messageId));
    }

    @Override
    public synchronized void complete(String messageId, String attemptId, DeliveryResult result) {
        DeliveryLease current = leases.get(messageId);
        if (current != null && current.attemptId().equals(attemptId)) {
            leases.remove(messageId);
        }
    }

    @Override
    public synchronized List<String> findExpiredLeases(Instant now, int limit) {
        requireLimit(limit);
        return leases.values().stream()
                .filter(lease -> !lease.leaseUntil().isAfter(now))
                .sorted(Comparator.comparing(DeliveryLease::leaseUntil))
                .limit(limit)
                .map(DeliveryLease::messageId)
                .toList();
    }

    @Override
    public synchronized void appendAttempt(DeliveryAttempt attempt) {
        Deque<DeliveryAttempt> recent = attempts.computeIfAbsent(
                attempt.messageId(), ignored -> new ArrayDeque<>());
        recent.addFirst(attempt);
        while (recent.size() > MAX_ATTEMPTS) {
            recent.removeLast();
        }
    }

    @Override
    public synchronized List<DeliveryAttempt> recentAttempts(String messageId, int limit) {
        requireLimit(limit);
        Deque<DeliveryAttempt> recent = attempts.get(messageId);
        if (recent == null) {
            return List.of();
        }
        List<DeliveryAttempt> result = new ArrayList<>(Math.min(limit, recent.size()));
        int count = 0;
        for (DeliveryAttempt attempt : recent) {
            if (count++ >= limit) {
                break;
            }
            result.add(attempt);
        }
        return List.copyOf(result);
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("limit must be between 1 and 20");
        }
    }
}
