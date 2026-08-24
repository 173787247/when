package com.when.core;

import java.util.List;
import java.util.Optional;

/** Persistent message-fact storage contract. */
public interface StoragePlugin {
    String type();

    void create(Message message);

    Optional<Message> get(String messageId);

    boolean transition(
            String messageId,
            MessageStatus expected,
            MessageStatus target,
            StatePatch patch);

    List<Message> loadPendingByTimeWheel(String timeWheelId);

    void deleteExpired(String messageId);
}
