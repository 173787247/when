package com.when.timewheel;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

final class NamedThreadFactory implements ThreadFactory {
    private final String prefix;
    private final AtomicInteger sequence = new AtomicInteger();

    NamedThreadFactory(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, prefix + '-' + sequence.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }
}
