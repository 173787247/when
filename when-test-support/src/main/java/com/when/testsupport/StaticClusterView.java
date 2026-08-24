package com.when.testsupport;

import com.when.core.ClusterView;
import com.when.core.NodeEndpoint;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable first-run placement map. Dynamic allocation remains outside lesson 45. */
public final class StaticClusterView implements ClusterView {
    private final Map<String, NodeEndpoint> masters;

    public StaticClusterView(Map<String, NodeEndpoint> masters) {
        Objects.requireNonNull(masters, "masters");
        masters.forEach((timeWheelId, endpoint) -> {
            if (timeWheelId == null || timeWheelId.isBlank()) {
                throw new IllegalArgumentException("timeWheelId must not be blank");
            }
            Objects.requireNonNull(endpoint, "endpoint");
        });
        this.masters = Map.copyOf(masters);
    }

    @Override
    public Optional<NodeEndpoint> masterOf(String timeWheelId) {
        if (timeWheelId == null || timeWheelId.isBlank()) {
            throw new IllegalArgumentException("timeWheelId must not be blank");
        }
        return Optional.ofNullable(masters.get(timeWheelId));
    }
}
