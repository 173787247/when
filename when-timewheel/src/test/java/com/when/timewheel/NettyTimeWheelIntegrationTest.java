package com.when.timewheel;

import static com.when.timewheel.TimeWheelTestSupport.message;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class NettyTimeWheelIntegrationTest {
    @Test
    void realHashedWheelTimerTriggersWithinSecondLevelTolerance() throws Exception {
        CountDownLatch due = new CountDownLatch(1);
        TimeWheelConfig config = new TimeWheelConfig(10L, 64, 100L, 1, 16);
        NettyTimeWheel wheel = new NettyTimeWheel("real-wheel", id -> due.countDown(), Clock.systemUTC(), config);
        try {
            wheel.start();
            wheel.add(message("real-message", "real-wheel", System.currentTimeMillis() + 80L));

            assertTrue(due.await(2, TimeUnit.SECONDS));
        } finally {
            wheel.stop();
        }
    }

    @Test
    void realBackendDefersRebuiltEntriesUntilExplicitStart() throws Exception {
        CountDownLatch due = new CountDownLatch(1);
        TimeWheelConfig config = new TimeWheelConfig(10L, 64, 100L, 1, 16);
        NettyTimeWheel wheel = new NettyTimeWheel("rebuild-wheel", id -> due.countDown(), Clock.systemUTC(), config);
        try {
            wheel.add(message("overdue", "rebuild-wheel", System.currentTimeMillis() - 1L));
            assertFalse(due.await(80, TimeUnit.MILLISECONDS));

            wheel.start();
            assertTrue(due.await(2, TimeUnit.SECONDS));
        } finally {
            wheel.stop();
        }
    }
}
