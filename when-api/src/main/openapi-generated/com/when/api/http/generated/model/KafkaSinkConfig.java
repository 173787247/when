package com.when.api.http.generated.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record KafkaSinkConfig(
        @NotBlank String bootstrapServers,
        @NotBlank @Size(max = 249) String topic,
        @Size(max = 1024) String key,
        Map<String, String> headers) {
}
