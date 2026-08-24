package com.when.core;

import java.util.concurrent.CompletionStage;

/** Executes an assignment already committed by the Controller; it does not choose placements. */
public interface FailoverExecutor {
    CompletionStage<PromotionResult> applyAssignment(TimeWheelAssignment assignment);
}
