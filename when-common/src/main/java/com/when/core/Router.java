package com.when.core;

/** Maps a message ID to a stable time-wheel ID. */
public interface Router {
    String routeToTimeWheel(String messageId);
}
