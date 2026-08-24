package com.when.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.when.core.SinkType;
import com.when.sink.http.HttpSink;
import com.when.sink.kafka.KafkaSink;
import com.when.sink.file.FileSink;
import com.when.sink.spi.SinkRegistry;
import org.junit.jupiter.api.Test;

class ProductionSinkWiringTest {
    @Test
    void productionClasspathLoadsRealSinkPlugins() {
        try (SinkRegistry sinks = SinkRegistry.load()) {
            assertEquals(HttpSink.class, sinks.require(SinkType.HTTP).getClass());
            assertEquals(KafkaSink.class, sinks.require(SinkType.KAFKA).getClass());
            assertEquals(FileSink.class, sinks.require(SinkType.FILE).getClass());
        }
    }
}
