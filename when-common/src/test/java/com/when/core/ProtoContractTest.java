package com.when.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.when.common.proto.HttpSinkConfig;
import com.when.common.proto.SinkConfig;
import org.junit.jupiter.api.Test;

class ProtoContractTest {

    @Test
    void sinkConfigUsesARealOneof() {
        SinkConfig config = SinkConfig.newBuilder()
                .setHttp(HttpSinkConfig.newBuilder()
                        .setUrl("https://example.internal/callback")
                        .setMethod("POST")
                        .setTimeoutMs(5_000)
                        .build())
                .build();

        assertTrue(config.hasHttp());
        assertFalse(config.hasKafka());
        assertEquals(SinkConfig.ConfigCase.HTTP, config.getConfigCase());
    }

    @Test
    void commonEnumsKeepTheirWireNumbers() {
        assertEquals(0, com.when.common.proto.SinkType.SINK_TYPE_UNSPECIFIED.getNumber());
        assertEquals(1, com.when.common.proto.SinkType.HTTP.getNumber());
        assertEquals(2, com.when.common.proto.SinkType.KAFKA.getNumber());
        assertEquals(1, com.when.common.proto.MessageStatus.PENDING.getNumber());
        assertEquals(5, com.when.common.proto.MessageStatus.CANCELLED.getNumber());
    }
}
