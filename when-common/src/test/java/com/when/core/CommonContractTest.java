package com.when.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CommonContractTest {

    @Test
    void stateMachineAllowsOnlyDocumentedTransitions() {
        assertTrue(MessageStatus.PENDING.canTransitionTo(MessageStatus.DELIVERING));
        assertTrue(MessageStatus.PENDING.canTransitionTo(MessageStatus.CANCELLED));
        assertTrue(MessageStatus.DELIVERING.canTransitionTo(MessageStatus.DELIVERED));
        assertTrue(MessageStatus.DELIVERING.canTransitionTo(MessageStatus.PENDING));
        assertTrue(MessageStatus.DELIVERING.canTransitionTo(MessageStatus.FAILED));

        assertFalse(MessageStatus.PENDING.canTransitionTo(MessageStatus.DELIVERED));
        assertFalse(MessageStatus.DELIVERED.canTransitionTo(MessageStatus.PENDING));
        assertFalse(MessageStatus.FAILED.canTransitionTo(MessageStatus.PENDING));
        assertFalse(MessageStatus.CANCELLED.canTransitionTo(MessageStatus.PENDING));
        assertFalse(MessageStatus.PENDING.canTransitionTo(null));
    }

    @Test
    void messageDoesNotExposeMutablePayloadStorage() {
        byte[] source = {1, 2, 3};
        Message message = message(source);

        source[0] = 9;
        assertEquals(1, message.payload()[0]);

        byte[] returned = message.payload();
        returned[1] = 9;
        assertEquals(2, message.payload()[1]);
    }

    @Test
    void optionalPayloadMayBeAbsent() {
        assertNull(message(null).payload());
    }

    @Test
    void sinkHeadersAreImmutableSnapshots() {
        Map<String, String> source = new HashMap<>();
        source.put("X-Correlation-Id", "public-value");
        HttpSinkConfig config = new HttpSinkConfig(
                "https://example.internal/callback", "POST", source, 5_000);

        source.clear();
        assertEquals("public-value", config.headers().get("X-Correlation-Id"));
        assertThrows(UnsupportedOperationException.class,
                () -> config.headers().put("another", "value"));
    }

    private static Message message(byte[] payload) {
        HttpSinkConfig config = new HttpSinkConfig(
                "https://example.internal/callback", "POST", Map.of(), 5_000);
        return new Message(
                "message-1",
                1_000L,
                2_000L,
                "tw-1",
                SinkType.HTTP,
                config,
                payload,
                "orders",
                MessageStatus.PENDING,
                0,
                2_000L,
                0L,
                null,
                "trace-1");
    }
}
