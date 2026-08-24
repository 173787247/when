package com.when.cluster.replica;

import com.when.core.TimeWheelAssignment;
import com.when.core.Message;
import com.when.core.MessageStatus;
import com.when.core.StatePatch;
import com.when.core.StoragePlugin;
import com.when.core.TimeWheel;
import com.when.core.TimeWheelRegistry;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

final class ReplicaTestSupport {
    private ReplicaTestSupport() {
    }

    static Message message(String id, String twId, long deliverAt) {
        return new Message(
                id,
                0,
                deliverAt,
                twId,
                null,
                null,
                null,
                null,
                MessageStatus.PENDING,
                0,
                deliverAt,
                0,
                null,
                null);
    }

    static final class AssignmentStore implements ReplicaAssignmentStore {
        private final Map<String, TimeWheelAssignment> values = new ConcurrentHashMap<>();

        void put(TimeWheelAssignment assignment) {
            values.put(assignment.twId(), assignment);
        }

        @Override
        public Optional<TimeWheelAssignment> current(String twId) {
            return Optional.ofNullable(values.get(twId));
        }

        @Override
        public boolean markSyncState(String twId, long assignmentVersion, String syncState) {
            return values.computeIfPresent(twId, (ignored, current) -> {
                if (current.assignmentVersion() != assignmentVersion) {
                    return current;
                }
                return new TimeWheelAssignment(
                        current.twId(),
                        current.master(),
                        current.slave(),
                        current.status(),
                        syncState,
                        current.assignmentVersion());
            }) != null && values.get(twId).assignmentVersion() == assignmentVersion;
        }
    }

    static final class RecordingWheel implements TimeWheel {
        private final String id;
        private final Map<String, Message> messages = new LinkedHashMap<>();
        private boolean started;

        RecordingWheel(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public synchronized void add(Message message) {
            messages.put(message.messageId(), message);
        }

        @Override
        public synchronized void remove(String messageId) {
            messages.remove(messageId);
        }

        @Override
        public synchronized void start() {
            started = true;
        }

        @Override
        public synchronized void stop() {
            started = false;
        }

        synchronized boolean contains(String messageId) {
            return messages.containsKey(messageId);
        }

        synchronized int size() {
            return messages.size();
        }

        synchronized boolean started() {
            return started;
        }
    }

    static final class Registry implements TimeWheelRegistry {
        private final TimeWheel wheel;

        Registry(TimeWheel wheel) {
            this.wheel = wheel;
        }

        @Override
        public TimeWheel require(String timeWheelId) {
            if (!wheel.id().equals(timeWheelId)) {
                throw new IllegalArgumentException("unknown wheel");
            }
            return wheel;
        }

        @Override
        public Collection<TimeWheel> all() {
            return List.of(wheel);
        }
    }

    static class Storage implements StoragePlugin {
        volatile List<Message> pending = List.of();

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
            return pending;
        }

        @Override
        public void deleteExpired(String messageId) {
            throw new UnsupportedOperationException();
        }
    }
}
