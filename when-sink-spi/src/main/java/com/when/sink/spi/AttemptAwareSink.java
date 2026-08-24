package com.when.sink.spi;

import com.when.core.DeliveryResult;
import com.when.core.Message;
import com.when.core.Sink;

/** Optional Sink extension that receives the durable attempt identifier. */
public interface AttemptAwareSink extends Sink {
    DeliveryResult deliver(Message message, String attemptId);

    @Override
    default DeliveryResult deliver(Message message) {
        return deliver(message, DeliveryAttemptIds.next());
    }
}
