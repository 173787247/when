package com.when.sink.kafka;

import java.util.Map;
import org.apache.kafka.clients.producer.Producer;

/** Testable creation boundary for official Kafka Producer instances. */
@FunctionalInterface
public interface KafkaProducerFactory {
    Producer<String, byte[]> create(Map<String, Object> properties);
}
