package com.when.api.http.generated.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MessagePageData(
        List<MessageSummary> items,
        String nextCursor,
        boolean hasMore,
        long indexUpdatedAt) {
    public MessagePageData { items = List.copyOf(items); }
}
