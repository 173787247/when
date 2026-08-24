package com.when.api.http.generated.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SubmitMessageRequest(
        Long deliverAt,
        @Min(1) @Max(2592000) Long delaySeconds,
        @NotNull SinkType sinkType,
        @NotNull @Valid SinkConfig sinkConfig,
        @Size(max = 87384) String payload,
        @Size(max = 128) String businessTag) {
}
