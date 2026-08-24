package com.when.ingress.router;

import com.when.core.Router;
import com.when.core.TimeWheel;
import com.when.core.TimeWheelRegistry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable consistent-hash ring over stable logical time-wheel IDs. */
public final class ConsistentHashRouter implements Router {
    public static final int DEFAULT_VIRTUAL_NODES = 128;

    private final NavigableMap<Long, String> ring;

    public ConsistentHashRouter(Collection<String> timeWheelIds) {
        this(timeWheelIds, DEFAULT_VIRTUAL_NODES);
    }

    public ConsistentHashRouter(Collection<String> timeWheelIds, int virtualNodes) {
        Objects.requireNonNull(timeWheelIds, "timeWheelIds");
        if (virtualNodes < 1) {
            throw new IllegalArgumentException("virtualNodes must be positive");
        }
        TreeMap<Long, String> entries = new TreeMap<>(Long::compareUnsigned);
        timeWheelIds.stream().distinct().sorted().forEach(id -> {
            requireText(id, "timeWheelId");
            for (int replica = 0; replica < virtualNodes; replica++) {
                entries.put(hash(id + '#' + replica), id);
            }
        });
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("at least one time wheel is required");
        }
        this.ring = java.util.Collections.unmodifiableNavigableMap(entries);
    }

    public static ConsistentHashRouter fromRegistry(TimeWheelRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        return new ConsistentHashRouter(registry.all().stream().map(TimeWheel::id).toList());
    }

    @Override
    public String routeToTimeWheel(String messageId) {
        requireText(messageId, "messageId");
        long point = hash(messageId);
        var tail = ring.ceilingEntry(point);
        return (tail == null ? ring.firstEntry() : tail).getValue();
    }

    private static long hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
