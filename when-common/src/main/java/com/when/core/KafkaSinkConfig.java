package com.when.core;

import java.util.Map;

/** Configuration for a Kafka delivery target. */
public record KafkaSinkConfig(
        String bootstrapServers,
        String topic,
        String key,
        Map<String, String> headers) implements SinkConfig {

    public KafkaSinkConfig {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    @Override
    public SinkType type() {
        return SinkType.KAFKA;
    }
}
