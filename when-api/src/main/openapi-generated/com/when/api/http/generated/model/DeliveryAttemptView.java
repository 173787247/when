package com.when.api.http.generated.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DeliveryAttemptView(
        String attemptId,
        long startedAt,
        long finishedAt,
        String result,
        String errorCode,
        String errorSummary,
        long durationMs) {
}
