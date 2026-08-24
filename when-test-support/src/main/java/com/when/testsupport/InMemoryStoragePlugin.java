package com.when.testsupport;

import com.when.core.Message;
import com.when.core.MessageStatus;
import com.when.core.StatePatch;
import com.when.core.StoragePlugin;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Thread-safe public-contract storage fake for first-run unit and wiring checks. */
public final class InMemoryStoragePlugin implements StoragePlugin {
    private final ConcurrentMap<String, Message> messages = new ConcurrentHashMap<>();

    @Override
    public String type() {
        return "test-memory";
    }

    @Override
    public void create(Message message) {
        if (message == null || message.status() != MessageStatus.PENDING) {
            throw new IllegalArgumentException("new message must be PENDING");
        }
        if (messages.putIfAbsent(message.messageId(), message) != null) {
            throw new IllegalStateException("message already exists");
        }
    }

    @Override
    public Optional<Message> get(String messageId) {
        return Optional.ofNullable(messages.get(messageId));
    }

    @Override
    public boolean transition(
            String messageId,
            MessageStatus expected,
            MessageStatus target,
            StatePatch patch) {
        if (!expected.canTransitionTo(target)) {
            throw new IllegalArgumentException("illegal message status transition");
        }
        StatePatch effective = patch == null ? new StatePatch(null, null, null, null) : patch;
        AtomicBoolean changed = new AtomicBoolean();
        messages.computeIfPresent(messageId, (ignored, current) -> {
            if (current.status() != expected) {
                return current;
            }
            changed.set(true);
            return new Message(
                    current.messageId(),
                    current.createdAt(),
                    current.deliverAt(),
                    current.timeWheelId(),
                    current.sinkType(),
                    current.sinkConfig(),
                    current.payload(),
                    current.businessTag(),
                    target,
                    effective.retryCount() == null ? current.retryCount() : effective.retryCount(),
                    effective.nextAttemptAt() == null ? current.nextAttemptAt() : effective.nextAttemptAt(),
                    effective.deliveredAt() == null ? current.deliveredAt() : effective.deliveredAt(),
                    effective.lastError() == null ? current.lastError() : effective.lastError(),
                    current.traceId());
        });
        return changed.get();
    }

    @Override
    public List<Message> loadPendingByTimeWheel(String timeWheelId) {
        return messages.values().stream()
                .filter(message -> message.status() == MessageStatus.PENDING)
                .filter(message -> message.timeWheelId().equals(timeWheelId))
                .sorted(java.util.Comparator.comparingLong(Message::nextAttemptAt))
                .toList();
    }

    @Override
    public void deleteExpired(String messageId) {
        messages.computeIfPresent(messageId, (ignored, current) -> switch (current.status()) {
            case DELIVERED, FAILED, CANCELLED -> null;
            case PENDING, DELIVERING -> current;
        });
    }
}
