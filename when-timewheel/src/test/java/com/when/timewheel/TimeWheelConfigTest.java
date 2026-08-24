package com.when.timewheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TimeWheelConfigTest {
    @Test
    void defaultsAreBoundedAndUseDocumentedTimerShape() {
        TimeWheelConfig config = TimeWheelConfig.defaults();

        assertEquals(100L, config.tickMillis());
        assertEquals(512, config.wheelSize());
        assertEquals(1, config.dueThreads());
        assertEquals(1_024, config.dueQueueCapacity());
    }

    @Test
    void wheelSizeMustBeAPowerOfTwo() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TimeWheelConfig(100L, 500, 10L, 1, 10));
    }
}
