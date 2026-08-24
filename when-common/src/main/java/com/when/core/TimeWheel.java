package com.when.core;

/** Rebuildable in-memory scheduling index. */
public interface TimeWheel {
    String id();

    void add(Message message);

    void remove(String messageId);

    void start();

    void stop();
}
