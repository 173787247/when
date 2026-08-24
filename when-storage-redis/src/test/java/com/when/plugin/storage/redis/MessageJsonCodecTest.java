package com.when.plugin.storage.redis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.when.core.HttpSinkConfig;
import com.when.core.KafkaSinkConfig;
import com.when.core.Message;
import com.when.core.MessageStatus;
import com.when.core.SinkConfig;
import com.when.core.SinkType;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessageJsonCodecTest {
    private final MessageJsonCodec codec = new MessageJsonCodec();

    @Test
    void roundTripsHttpConfigurationWithoutPolymorphicClassMetadata() {
        assertRoundTrip(new HttpSinkConfig(
                "https://example.invalid/callback", "POST", Map.of("X-Request", "safe"), 4_000));
    }

    @Test
    void roundTripsKafkaConfigurationWithoutPolymorphicClassMetadata() {
        assertRoundTrip(new KafkaSinkConfig(
                "broker.internal:9092", "delayed-events", "key-1", Map.of("source", "when")));
    }

    private void assertRoundTrip(SinkConfig sinkConfig) {
        Message original = new Message(
                "message-1",
                1_000,
                2_000,
                "wheel-1",
                sinkConfig.type(),
                sinkConfig,
                new byte[] {7, 8, 9},
                "tag",
                MessageStatus.PENDING,
                2,
                2_500,
                0,
                "non-sensitive-error",
                "trace-1");

        String encoded = codec.encode(original);
        Message decoded = codec.decode(encoded);

        assertEquals(original.messageId(), decoded.messageId());
        assertEquals(original.createdAt(), decoded.createdAt());
        assertEquals(original.deliverAt(), decoded.deliverAt());
        assertEquals(original.timeWheelId(), decoded.timeWheelId());
        assertEquals(original.sinkType(), decoded.sinkType());
        assertEquals(original.sinkConfig(), decoded.sinkConfig());
        assertArrayEquals(original.payload(), decoded.payload());
        assertEquals(original.businessTag(), decoded.businessTag());
        assertEquals(original.status(), decoded.status());
        assertEquals(original.retryCount(), decoded.retryCount());
        assertEquals(original.nextAttemptAt(), decoded.nextAttemptAt());
        assertEquals(original.deliveredAt(), decoded.deliveredAt());
        assertEquals(original.lastError(), decoded.lastError());
        assertEquals(original.traceId(), decoded.traceId());
        assertEquals(-1, encoded.indexOf("com.when"));
        assertEquals(sinkConfig.type(), SinkType.valueOf(decoded.sinkType().name()));
    }
}
