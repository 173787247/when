package com.when.cluster.controller;

import java.util.List;

/** Pure deterministic calculation; implementations must not access etcd or executors. */
public interface RebalancePlanner {
    List<MoveAction> plan(ClusterState state, RebalancePolicy policy);
}
