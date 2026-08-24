package com.when.cluster.membership;

import com.when.cluster.etcd.TimeWheelMetadata;

import java.util.List;
import java.util.Map;

/** ETCD state loaded when this node becomes Controller; no decisions are made in lesson 43. */
public record ControllerSnapshot(
        List<NodeInfo> nodes,
        Map<String, TimeWheelMetadata> timeWheels) {
    public ControllerSnapshot {
        nodes = List.copyOf(nodes);
        timeWheels = Map.copyOf(timeWheels);
    }
}
