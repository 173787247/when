package com.when.core;

/** Marker for strongly typed sink configuration. */
public sealed interface SinkConfig permits HttpSinkConfig, KafkaSinkConfig {
    SinkType type();
}
