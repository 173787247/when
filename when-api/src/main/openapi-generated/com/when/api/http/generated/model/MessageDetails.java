package com.when.api.http.generated.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MessageDetails(
        String messageId,
        MessageStatus status,
        long createdAt,
        long deliverAt,
        long deliveredAt,
        SinkType sinkType,
        String businessTag,
        int retryCount,
        String lastError,
        List<DeliveryAttemptView> attempts) {
    public MessageDetails {
        attempts = List.copyOf(attempts);
    }
}
