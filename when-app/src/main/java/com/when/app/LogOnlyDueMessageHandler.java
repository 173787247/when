package com.when.app;

import com.when.core.DueMessageHandler;
import com.when.core.Message;
import com.when.core.MessageStatus;
import com.when.core.StatePatch;
import com.when.core.StoragePlugin;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Single-node integration-test due handler.
 *
 * <p>It atomically claims a due message and logs only safe identifiers. It deliberately does not
 * invoke an external Sink and therefore never marks a message as delivered.</p>
 */
final class LogOnlyDueMessageHandler implements DueMessageHandler {
    private static final Logger LOGGER = Logger.getLogger(LogOnlyDueMessageHandler.class.getName());

    private final StoragePlugin storage;

    LogOnlyDueMessageHandler(StoragePlugin storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public void onDue(String messageId) {
        Message message = storage.get(messageId).orElse(null);
        if (message == null || !storage.transition(
                messageId,
                MessageStatus.PENDING,
                MessageStatus.DELIVERING,
                new StatePatch(null, null, null, null))) {
            return;
        }
        LOGGER.info(() -> "event=when_due message_id=" + message.messageId()
                + " trace_id=" + safe(message.traceId())
                + " tw_id=" + message.timeWheelId());
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
