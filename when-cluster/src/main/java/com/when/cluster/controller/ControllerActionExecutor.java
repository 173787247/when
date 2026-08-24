package com.when.cluster.controller;

import java.util.concurrent.CompletionStage;

/** Executes a decision only after the authoritative assignment transaction has committed. */
@FunctionalInterface
public interface ControllerActionExecutor {
    CompletionStage<Void> execute(
            AssignmentDecision decision,
            AssignmentRecord committedAssignment);
}
