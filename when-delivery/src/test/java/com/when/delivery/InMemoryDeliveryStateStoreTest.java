package com.when.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemoryDeliveryStateStoreTest {
    @Test
    void retainsOnlyLatestTwentyAttempts() {
        InMemoryDeliveryStateStore store = new InMemoryDeliveryStateStore();
        Instant base = Instant.now();
        for (int index = 0; index < 25; index++) {
            store.appendAttempt(new DeliveryAttempt(
                    "message-1",
                    "attempt-" + index,
                    base.plusSeconds(index),
                    base.plusSeconds(index + 1L),
                    false,
                    true,
                    "TEMPORARY",
                    1));
        }

        var attempts = store.recentAttempts("message-1", 20);
        assertEquals(20, attempts.size());
        assertEquals("attempt-24", attempts.get(0).attemptId());
        assertEquals("attempt-5", attempts.get(19).attemptId());
    }
}
