package com.when.cluster.controller;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Durable replay cursor written atomically with each assignment decision. */
public record ControllerProgress(
        long term,
        @JsonProperty("last_revision") long lastRevision,
        @JsonProperty("decision_id") String decisionId) {
    public ControllerProgress {
        if (term <= 0 || lastRevision < 0) {
            throw new IllegalArgumentException("term must be positive and revision non-negative");
        }
        decisionId = Text.require(decisionId, "decisionId");
    }
}
