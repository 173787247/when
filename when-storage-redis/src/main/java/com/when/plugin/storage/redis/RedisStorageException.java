package com.when.plugin.storage.redis;

/** Indicates that the Redis message-fact store could not complete an operation. */
public final class RedisStorageException extends RuntimeException {
    public RedisStorageException(String message) {
        super(message);
    }

    public RedisStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
