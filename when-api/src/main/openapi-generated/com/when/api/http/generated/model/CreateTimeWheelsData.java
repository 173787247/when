package com.when.api.http.generated.model;

import java.util.List;

public record CreateTimeWheelsData(List<String> created, List<TimeWheelView> assignments) {
    public CreateTimeWheelsData {
        created = List.copyOf(created);
        assignments = List.copyOf(assignments);
    }
}
