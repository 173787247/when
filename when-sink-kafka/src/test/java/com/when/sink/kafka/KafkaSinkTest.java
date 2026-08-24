package com.when.sink.kafka;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.when.core.KafkaSinkConfig;
import com.when.core.Message;
import com.when.core.MessageStatus;
import com.when.core.SinkType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

class KafkaSinkTest {
    @Test
    void reusesProducerAndWritesPayloadKeyAndIdentityHeaders() {
        AtomicInteger creations = new AtomicInteger();
        AtomicReference<MockProducer<String, byte[]>> producerReference = new AtomicReference<>();
        KafkaProducerFactory factory = ignored -> {
            creations.incrementAndGet();
            MockProducer<String, byte[]> producer =
                    new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
            producerReference.set(producer);
            return producer;
        };
        KafkaSink sink = new KafkaSink(factory);

        assertTrue(sink.deliver(message("events"), "attempt-1").success());
        assertTrue(sink.deliver(message("events"), "attempt-2").success());

        assertEquals(1, creations.get());
        assertEquals(1, sink.cachedProducerCount());
        var record = producerReference.get().history().get(0);
        assertEquals("message-1", record.key());
        assertArrayEquals("payload".getBytes(StandardCharsets.UTF_8), record.value());
        assertNotNull(record.headers().lastHeader("X-When-Message-Id"));
        sink.close();
    }

    @Test
    void differentProducerConfigurationFingerprintsDoNotShare() {
        AtomicInteger creations = new AtomicInteger();
        KafkaProducerFactory factory = ignored -> {
            creations.incrementAndGet();
            return new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
        };
        KafkaProducerSettingsResolver resolver = config -> {
            Map<String, Object> settings = new HashMap<>();
            settings.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers());
            settings.put("security.protocol", config.topic().equals("events-a") ? "PLAINTEXT" : "SSL");
            return settings;
        };
        KafkaSink sink = new KafkaSink(
                factory, resolver, 4, Duration.ofMinutes(1), Duration.ofSeconds(1));

        assertTrue(sink.deliver(message("events-a"), "attempt-a").success());
        assertTrue(sink.deliver(message("events-b"), "attempt-b").success());

        assertEquals(2, creations.get());
        sink.close();
    }

    private static Message message(String topic) {
        long now = System.currentTimeMillis();
        return new Message(
                "message-1", now, now, "tw-0", SinkType.KAFKA,
                new KafkaSinkConfig("broker.invalid:9092", topic, "", Map.of()),
                "payload".getBytes(StandardCharsets.UTF_8), null, MessageStatus.DELIVERING,
                0, now, 0, null, "trace-1");
    }
}
