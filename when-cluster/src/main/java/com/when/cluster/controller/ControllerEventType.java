package com.when.cluster.controller;

public enum ControllerEventType {
    NODE_JOINED,
    NODE_LEFT,
    SLAVE_OUT_OF_SYNC,
    PERIODIC_REBALANCE,
    TIME_WHEEL_CHANGED
}
