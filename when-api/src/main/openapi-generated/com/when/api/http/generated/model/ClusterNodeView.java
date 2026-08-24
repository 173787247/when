package com.when.api.http.generated.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ClusterNodeView(
        String nodeId,
        String host,
        int grpcPort,
        long startTime,
        double load,
        boolean ready,
        boolean controller) {
}
