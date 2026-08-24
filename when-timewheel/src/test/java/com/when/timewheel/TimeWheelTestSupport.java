package com.when.timewheel;

import com.when.core.Message;
import com.when.core.MessageStatus;
import com.when.core.SinkType;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class TimeWheelTestSupport {
    private TimeWheelTestSupport() {}

    static Message message(String messageId, String wheelId, long nextAttemptAt) {
        return new Message(
                messageId,
                1L,
                nextAttemptAt,
                wheelId,
                SinkType.HTTP,
                null,
                null,
                null,
                MessageStatus.PENDING,
                0,
                nextAttemptAt,
                0L,
                null,
                "trace");
    }

    static ThreadPoolExecutor executor(String wheelId, int threads, int queueCapacity) {
        return new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new NamedThreadFactory("when-tw-" + wheelId + "-due-test"),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
