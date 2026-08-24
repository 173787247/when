package com.when.api.http.generated.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TimeWheelView(
        String twId,
        String master,
        String slave,
        String status,
        String syncState,
        long assignmentVersion,
        long messageCount) {
}
