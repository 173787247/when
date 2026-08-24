package com.when.app.logging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;

class StructuredJsonEncoderTest {
    @Test
    void emitsRequiredTraceFieldsAndDropsUnregisteredSensitiveFields() {
        LoggingEvent event = new LoggingEvent();
        event.setTimeStamp(1_700_000_000_000L);
        event.setLevel(Level.INFO);
        event.setLoggerName("com.when.Test");
        event.setMessage("message delivered");
        event.setMDCPropertyMap(Map.of(
                "trace_id", "4bf92f3577b34da6a3ce929d0e0e4736",
                "span_id", "00f067aa0ba902b7",
                "payload", "must-not-appear"));
        event.addKeyValuePair(new KeyValuePair("event", "message_delivered"));
        event.addKeyValuePair(new KeyValuePair("message_id", "message-1"));
        event.addKeyValuePair(new KeyValuePair("sink_config", "must-not-appear"));

        String encoded = new String(new StructuredJsonEncoder().encode(event), StandardCharsets.UTF_8);

        assertTrue(encoded.contains("\"event\":\"message_delivered\""));
        assertTrue(encoded.contains("\"trace_id\":\"4bf92f3577b34da6a3ce929d0e0e4736\""));
        assertTrue(encoded.contains("\"span_id\":\"00f067aa0ba902b7\""));
        assertTrue(encoded.contains("\"message_id\":\"message-1\""));
        assertFalse(encoded.contains("payload"));
        assertFalse(encoded.contains("sink_config"));
        assertFalse(encoded.contains("must-not-appear"));
    }
}
