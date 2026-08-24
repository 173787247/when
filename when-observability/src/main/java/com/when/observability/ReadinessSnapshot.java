package com.when.observability;

import java.util.List;

public record ReadinessSnapshot(boolean ready, List<ReadinessReason> reasons) {
    public ReadinessSnapshot {
        reasons = List.copyOf(reasons);
        if (ready && !reasons.isEmpty()) {
            throw new IllegalArgumentException("a ready snapshot cannot contain failure reasons");
        }
    }
}
