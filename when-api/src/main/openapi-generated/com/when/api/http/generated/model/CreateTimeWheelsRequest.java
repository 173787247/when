package com.when.api.http.generated.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreateTimeWheelsRequest(@NotNull @Min(1) @Max(100) Integer count) {
}
