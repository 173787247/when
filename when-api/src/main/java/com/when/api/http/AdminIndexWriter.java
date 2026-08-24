package com.when.api.http;

/** Best-effort management index hook; failures must never roll back a durable submit. */
@FunctionalInterface
public interface AdminIndexWriter {
    void index(String messageId, long deliverAt);

    static AdminIndexWriter noop() {
        return (messageId, deliverAt) -> { };
    }
}
