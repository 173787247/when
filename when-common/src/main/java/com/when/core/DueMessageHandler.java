package com.when.core;

/** Asynchronous handoff invoked by a timer when a message becomes due. */
@FunctionalInterface
public interface DueMessageHandler {
    void onDue(String messageId);
}
