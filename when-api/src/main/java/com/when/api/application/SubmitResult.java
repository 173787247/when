package com.when.api.application;

import com.when.core.MessageStatus;

/** Result of application-layer submission. */
public record SubmitResult(String messageId, MessageStatus status, long deliverAt) {
}
