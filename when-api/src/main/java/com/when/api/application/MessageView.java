package com.when.api.application;

import com.when.core.MessageStatus;
import com.when.core.SinkType;

/** Non-sensitive message projection returned by Query. */
public record MessageView(
        String messageId,
        MessageStatus status,
        long createdAt,
        long deliverAt,
        long deliveredAt,
        int retryCount,
        String lastError,
        SinkType sinkType,
        String businessTag) {
}
