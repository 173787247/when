package com.when.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InMemoryTraceContextStoreTest {
    @Test
    void storesAndDeletesOnlyContextMetadata() {
        InMemoryTraceContextStore store = new InMemoryTraceContextStore();
        TraceContextSnapshot context = new TraceContextSnapshot(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                null,
                "4bf92f3577b34da6a3ce929d0e0e4736",
                "00f067aa0ba902b7",
                true);
        store.put("message-1", context);
        assertEquals(context, store.get("message-1").orElseThrow());
        store.delete("message-1");
        assertTrue(store.get("message-1").isEmpty());
    }
}
