package com.when.timewheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.when.core.Message;
import com.when.core.TimeWheel;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultTimeWheelRegistryTest {
    @Test
    void requiresExplicitUniqueRegistrationAndReturnsSnapshot() {
        StubWheel wheelA = new StubWheel("wheel-a");
        StubWheel wheelB = new StubWheel("wheel-b");
        DefaultTimeWheelRegistry registry = new DefaultTimeWheelRegistry(List.of(wheelA));

        registry.register(wheelB);

        assertSame(wheelA, registry.require("wheel-a"));
        assertEquals(2, registry.all().size());
        assertThrows(IllegalArgumentException.class, () -> registry.register(new StubWheel("wheel-a")));
        assertThrows(IllegalArgumentException.class, () -> registry.require("unknown"));
        assertSame(wheelB, registry.unregister("wheel-b"));
    }

    private record StubWheel(String id) implements TimeWheel {
        @Override
        public void add(Message message) {}

        @Override
        public void remove(String messageId) {}

        @Override
        public void start() {}

        @Override
        public void stop() {}
    }
}
