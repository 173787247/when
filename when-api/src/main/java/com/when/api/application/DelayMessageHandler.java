package com.when.api.application;

/** Application boundary used by the transport layer. The lesson 45 implementation owns all business logic. */
public interface DelayMessageHandler {
    SubmitResult submit(SubmitCommand command);

    MessageView query(String messageId);

    CancelResult cancel(String messageId);
}
