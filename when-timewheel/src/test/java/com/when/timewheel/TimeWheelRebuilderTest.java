package com.when.timewheel;

import static com.when.timewheel.TimeWheelTestSupport.executor;
import static com.when.timewheel.TimeWheelTestSupport.message;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.when.core.Message;
import com.when.core.MessageStatus;
import com.when.core.StatePatch;
import com.when.core.StoragePlugin;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TimeWheelRebuilderTest {
    @Test
    void reloadsPendingFactsBeforeStartingAndDoesNotLoseMessages() throws Exception {
        MutableClock clock = new MutableClock(0L);
        ManualTimerBackend backend = new ManualTimerBackend();
        CountDownLatch due = new CountDownLatch(2);
        List<String> delivered = new CopyOnWriteArrayList<>();
        List<Message> pending =
                List.of(message("one", "wheel-a", 100L), message("two", "wheel-a", 200L));
        RecordingStorage storage = new RecordingStorage(pending);
        NettyTimeWheel wheel =
                new NettyTimeWheel(
                        "wheel-a",
                        id -> {
                            delivered.add(id);
                            due.countDown();
                        },
                        clock,
                        backend,
                        executor("wheel-a", 1, 8),
                        10);

        try {
            int rebuilt = new TimeWheelRebuilder(storage).rebuildAndStart(wheel);

            assertEquals(2, rebuilt);
            assertEquals("wheel-a", storage.loadedWheelId);
            assertTrue(wheel.isStarted());
            assertEquals(2, wheel.pendingCount());
            backend.advanceBy(200L);
            assertTrue(due.await(1, TimeUnit.SECONDS));
            assertEquals(List.of("one", "two"), delivered);
        } finally {
            wheel.stop();
        }
    }

    private static final class RecordingStorage implements StoragePlugin {
        private final List<Message> pending;
        private String loadedWheelId;

        private RecordingStorage(List<Message> pending) {
            this.pending = pending;
        }

        @Override
        public String type() {
            return "test";
        }

        @Override
        public void create(Message message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Message> get(String messageId) {
            return Optional.empty();
        }

        @Override
        public boolean transition(
                String messageId,
                MessageStatus expected,
                MessageStatus target,
                StatePatch patch) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Message> loadPendingByTimeWheel(String timeWheelId) {
            loadedWheelId = timeWheelId;
            return pending;
        }

        @Override
        public void deleteExpired(String messageId) {
            throw new UnsupportedOperationException();
        }
    }
}
