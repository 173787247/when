package com.when.api.http.generated.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MessageSummary(
        String messageId,
        MessageStatus status,
        long createdAt,
        long deliverAt,
        long deliveredAt,
        SinkType sinkType,
        String businessTag,
        int retryCount,
        String lastError) {
}
