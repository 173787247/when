package com.when.timewheel;

import com.when.core.Message;
import com.when.core.StoragePlugin;
import com.when.core.TimeWheel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Recreates a time wheel's disposable in-memory index from authoritative persistent facts. */
public final class TimeWheelRebuilder {
    private final StoragePlugin storage;

    public TimeWheelRebuilder(StoragePlugin storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    /** Loads and indexes all pending messages for the supplied wheel without changing storage. */
    public int rebuild(TimeWheel wheel) {
        Objects.requireNonNull(wheel, "wheel");
        List<Message> pending =
                List.copyOf(
                        Objects.requireNonNull(
                                storage.loadPendingByTimeWheel(wheel.id()),
                                "storage returned a null pending list"));
        List<String> added = new ArrayList<>(pending.size());
        try {
            for (Message message : pending) {
                wheel.add(message);
                added.add(message.messageId());
            }
            return added.size();
        } catch (RuntimeException exception) {
            added.forEach(wheel::remove);
            throw exception;
        }
    }

    /** Rebuilds first, then marks the wheel started so callers can gate readiness on completion. */
    public int rebuildAndStart(TimeWheel wheel) {
        int rebuilt = rebuild(wheel);
        wheel.start();
        return rebuilt;
    }
}
