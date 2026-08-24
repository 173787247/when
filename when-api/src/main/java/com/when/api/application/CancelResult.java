package com.when.api.application;

import com.when.core.MessageStatus;

/** Result of a successful application-layer cancellation. */
public record CancelResult(String messageId, MessageStatus status) {
}
