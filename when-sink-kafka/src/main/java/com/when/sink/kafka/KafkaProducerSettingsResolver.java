package com.when.sink.kafka;

import com.when.core.KafkaSinkConfig;
import java.util.Map;

/** Resolves producer settings; secret values remain in memory and are represented only by a hash key. */
@FunctionalInterface
public interface KafkaProducerSettingsResolver {
    Map<String, Object> resolve(KafkaSinkConfig config);
}
