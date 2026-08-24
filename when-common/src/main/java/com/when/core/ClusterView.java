package com.when.core;

import java.util.Optional;

/** Read-only view used to resolve the current master for a stable time wheel. */
public interface ClusterView {
    Optional<NodeEndpoint> masterOf(String timeWheelId);
}
