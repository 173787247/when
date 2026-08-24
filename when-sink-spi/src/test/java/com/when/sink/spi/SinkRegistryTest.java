package com.when.sink.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.when.core.DeliveryResult;
import com.when.core.InvalidConfigException;
import com.when.core.Message;
import com.when.core.Sink;
import com.when.core.SinkConfig;
import com.when.core.SinkType;
import java.util.List;
import org.junit.jupiter.api.Test;

class SinkRegistryTest {
    @Test
    void indexesByTypeAndRejectsDuplicates() {
        Sink http = sink(SinkType.HTTP);
        Sink kafka = sink(SinkType.KAFKA);
        SinkRegistry registry = new SinkRegistry(List.of(http, kafka));

        assertEquals(http, registry.require(SinkType.HTTP));
        assertEquals(kafka, registry.require(SinkType.KAFKA));
        assertThrows(IllegalArgumentException.class,
                () -> new SinkRegistry(List.of(http, sink(SinkType.HTTP))));
    }

    private static Sink sink(SinkType type) {
        return new Sink() {
            @Override public SinkType type() { return type; }
            @Override public void validateConfig(SinkConfig config) throws InvalidConfigException { }
            @Override public DeliveryResult deliver(Message message) { return DeliveryResult.success(0); }
        };
    }
}
