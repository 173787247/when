package com.when.core;

/** Plugin contract for delivering due messages to a downstream system. */
public interface Sink {
    SinkType type();

    void validateConfig(SinkConfig config) throws InvalidConfigException;

    DeliveryResult deliver(Message message);

    default boolean healthCheck() {
        return true;
    }
}
