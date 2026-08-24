package com.when.api.http.generated.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Generated from openapi/when-v1.yaml. Do not edit the target copy. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiEnvelope<T>(String code, String message, String requestId, T data) {
    public static <T> ApiEnvelope<T> ok(String requestId, T data) {
        return new ApiEnvelope<>("OK", "success", requestId, data);
    }
}
