package com.when.cluster.controller;

/** Single-writer cluster decision boundary. Election itself belongs to cluster membership. */
public interface Controller {
    void onControllerElected(ControllerTerm term);

    void onClusterEvent(ClusterEvent event);

    TimeWheelAssignmentView createTimeWheel(TimeWheelSpec spec);
}
