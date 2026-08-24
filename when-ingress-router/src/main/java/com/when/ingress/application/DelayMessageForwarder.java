package com.when.ingress.application;

import com.when.api.application.CancelResult;
import com.when.api.application.SubmitResult;
import com.when.core.NodeEndpoint;

/** Internal transport boundary for operations that must execute on a remote Master. */
public interface DelayMessageForwarder extends AutoCloseable {
    SubmitResult submit(NodeEndpoint endpoint, RoutedSubmit submit);

    CancelResult cancel(NodeEndpoint endpoint, String messageId);

    static DelayMessageForwarder localOnly() {
        return new DelayMessageForwarder() {
            @Override
            public SubmitResult submit(NodeEndpoint endpoint, RoutedSubmit submit) {
                throw new IllegalStateException("remote forwarding is not configured");
            }

            @Override
            public CancelResult cancel(NodeEndpoint endpoint, String messageId) {
                throw new IllegalStateException("remote forwarding is not configured");
            }
        };
    }

    @Override
    default void close() {
    }
}
