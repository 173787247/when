package com.when.cluster.controller;

/** An election term is the etcd mod revision of {@code /when/controller}. */
public record ControllerTerm(String controllerNodeId, long term) {
    public ControllerTerm {
        controllerNodeId = Text.require(controllerNodeId, "controllerNodeId");
        if (term <= 0) {
            throw new IllegalArgumentException("term must be positive");
        }
    }

    public ControllerTerm(long term, String controllerNodeId) {
        this(controllerNodeId, term);
    }
}
