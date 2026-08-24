package com.when.api.http.generated.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record HttpSinkConfig(
        @NotBlank String url,
        String method,
        Map<String, String> headers,
        @Min(1) @Max(30000) Integer timeoutMs) {
}
