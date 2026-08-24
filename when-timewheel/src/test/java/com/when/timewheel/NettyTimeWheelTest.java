package com.when.timewheel;

import static com.when.timewheel.TimeWheelTestSupport.executor;
import static com.when.timewheel.TimeWheelTestSupport.message;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NettyTimeWheelTest {
    private final List<NettyTimeWheel> wheels = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopWheels() {
        wheels.forEach(NettyTimeWheel::stop);
    }

    @Test
    void calculatesDelayFromInjectableClockAndHandsOffAsynchronously() throws Exception {
        MutableClock clock = new MutableClock(1_000L);
        ManualTimerBackend backend = new ManualTimerBackend();
        CountDownLatch due = new CountDownLatch(1);
        List<String> handlerThreads = new CopyOnWriteArrayList<>();
        NettyTimeWheel wheel =
                wheel(
                        "wheel-a",
                        id -> {
                            handlerThreads.add(Thread.currentThread().getName());
                            due.countDown();
                        },
                        clock,
                        backend,
                        10);

        wheel.add(message("message-1", "wheel-a", 1_500L));

        assertEquals(500L, backend.lastDelayMillis());
        wheel.start();
        backend.advanceBy(499L);
        assertEquals(1L, due.getCount());
        backend.advanceBy(1L);
        assertTrue(due.await(1, TimeUnit.SECONDS));
        assertTrue(handlerThreads.get(0).contains("wheel-a-due-test"));
        assertEquals(0, wheel.pendingCount());
    }

    @Test
    void messagesLoadedDuringRebuildDoNotHandoffBeforeStart() throws Exception {
        ManualTimerBackend backend = new ManualTimerBackend();
        CountDownLatch due = new CountDownLatch(1);
        NettyTimeWheel wheel =
                wheel(
                        "wheel-a",
                        id -> due.countDown(),
                        Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC),
                        backend,
                        10);

        wheel.add(message("overdue", "wheel-a", 0L));
        backend.advanceBy(1L);
        assertFalse(due.await(50, TimeUnit.MILLISECONDS));

        wheel.start();
        backend.advanceBy(0L);
        assertTrue(due.await(1, TimeUnit.SECONDS));
    }

    @Test
    void duplicateAddAllowsOnlyLatestGenerationEvenIfCancelledCallbackRuns() throws Exception {
        ManualTimerBackend backend = new ManualTimerBackend();
        CountDownLatch due = new CountDownLatch(1);
        List<String> delivered = new CopyOnWriteArrayList<>();
        NettyTimeWheel wheel =
                wheel(
                        "wheel-a",
                        id -> {
                            delivered.add(id);
                            due.countDown();
                        },
                        Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC),
                        backend,
                        10);

        wheel.add(message("same-id", "wheel-a", 10L));
        long firstGeneration = wheel.generationOf("same-id");
        wheel.add(message("same-id", "wheel-a", 20L));
        long secondGeneration = wheel.generationOf("same-id");

        wheel.start();
        backend.advanceByIncludingCancelled(20L);
        assertTrue(due.await(1, TimeUnit.SECONDS));
        Thread.sleep(30L);
        assertEquals(List.of("same-id"), delivered);
        assertNotEquals(firstGeneration, secondGeneration);
        assertEquals(0, wheel.pendingCount());
    }

    @Test
    void removeIsConstantTimeAndStaleCallbackCannotHandoff() throws Exception {
        ManualTimerBackend backend = new ManualTimerBackend();
        CountDownLatch due = new CountDownLatch(1);
        NettyTimeWheel wheel = wheel("wheel-a", id -> due.countDown(), Clock.systemUTC(), backend, 10);

        wheel.add(message("cancelled", "wheel-a", System.currentTimeMillis() + 10L));
        wheel.remove("cancelled");
        wheel.start();
        backend.advanceByIncludingCancelled(100L);

        assertFalse(due.await(100, TimeUnit.MILLISECONDS));
        assertEquals(0, wheel.pendingCount());
    }

    @Test
    void enforcesPendingCapacityAndRejectsWrongWheel() {
        ManualTimerBackend backend = new ManualTimerBackend();
        MutableClock clock = new MutableClock(1_000L);
        NettyTimeWheel wheel = wheel("wheel-a", id -> {}, clock, backend, 1);
        long future = clock.millis() + Duration.ofDays(30).toMillis();

        wheel.add(message("one", "wheel-a", future));

        assertThrows(
                RejectedExecutionException.class,
                () -> wheel.add(message("two", "wheel-a", future)));
        assertThrows(
                IllegalArgumentException.class,
                () -> wheel.add(message("wrong", "wheel-b", future)));
        assertEquals(Duration.ofDays(30).toMillis(), backend.lastDelayMillis());
    }

    @Test
    void aBlockedWheelDoesNotBlockAnotherWheel() throws Exception {
        ManualTimerBackend backendA = new ManualTimerBackend();
        ManualTimerBackend backendB = new ManualTimerBackend();
        CountDownLatch aEntered = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        CountDownLatch bDue = new CountDownLatch(1);
        NettyTimeWheel wheelA =
                wheel(
                        "wheel-a",
                        id -> {
                            aEntered.countDown();
                            await(releaseA);
                        },
                        Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC),
                        backendA,
                        10);
        NettyTimeWheel wheelB =
                wheel(
                        "wheel-b",
                        id -> bDue.countDown(),
                        Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC),
                        backendB,
                        10);

        wheelA.add(message("a", "wheel-a", 1L));
        wheelB.add(message("b", "wheel-b", 1L));
        wheelA.start();
        wheelB.start();
        backendA.advanceBy(1L);
        assertTrue(aEntered.await(1, TimeUnit.SECONDS));
        backendB.advanceBy(1L);

        assertTrue(bDue.await(1, TimeUnit.SECONDS));
        releaseA.countDown();
    }

    @Test
    void fullDueQueueIsCountedInsteadOfBlockingTimerCallback() throws Exception {
        ManualTimerBackend backend = new ManualTimerBackend();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        NettyTimeWheel wheel =
                wheel(
                        "wheel-a",
                        id -> {
                            firstEntered.countDown();
                            await(release);
                        },
                        Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC),
                        backend,
                        10);

        wheel.add(message("one", "wheel-a", 1L));
        wheel.start();
        backend.advanceBy(1L);
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS));

        wheel.add(message("two", "wheel-a", 2L));
        wheel.add(message("three", "wheel-a", 2L));
        backend.advanceBy(2L);

        assertEquals(1L, wheel.rejectedDueHandoffCount());
        release.countDown();
    }

    @Test
    void startAndStopAreIdempotentAndStoppedWheelRejectsWork() {
        ManualTimerBackend backend = new ManualTimerBackend();
        NettyTimeWheel wheel = wheel("wheel-a", id -> {}, Clock.systemUTC(), backend, 10);

        wheel.start();
        wheel.start();
        assertTrue(wheel.isStarted());
        assertTrue(backend.isStarted());

        wheel.stop();
        wheel.stop();
        assertThrows(
                IllegalStateException.class,
                () -> wheel.add(message("late", "wheel-a", System.currentTimeMillis())));
    }

    private NettyTimeWheel wheel(
            String id,
            com.when.core.DueMessageHandler handler,
            Clock clock,
            TimerBackend backend,
            long capacity) {
        ThreadPoolExecutor dueExecutor = executor(id, 1, 1);
        NettyTimeWheel wheel =
                new NettyTimeWheel(id, handler, clock, backend, dueExecutor, capacity);
        wheels.add(wheel);
        return wheel;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
