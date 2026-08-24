package com.when.timewheel;

import com.when.core.TimeWheel;
import com.when.core.TimeWheelRegistry;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Explicit registry of time wheels hosted by the current node. */
public final class DefaultTimeWheelRegistry implements TimeWheelRegistry {
    private final ConcurrentMap<String, TimeWheel> wheels = new ConcurrentHashMap<>();

    public DefaultTimeWheelRegistry() {}

    public DefaultTimeWheelRegistry(Collection<? extends TimeWheel> wheels) {
        Objects.requireNonNull(wheels, "wheels").forEach(this::register);
    }

    public void register(TimeWheel wheel) {
        Objects.requireNonNull(wheel, "wheel");
        String id = requireId(wheel.id());
        TimeWheel existing = wheels.putIfAbsent(id, wheel);
        if (existing != null) {
            throw new IllegalArgumentException("time wheel already registered: " + id);
        }
    }

    public TimeWheel unregister(String timeWheelId) {
        return wheels.remove(requireId(timeWheelId));
    }

    @Override
    public TimeWheel require(String timeWheelId) {
        String id = requireId(timeWheelId);
        TimeWheel wheel = wheels.get(id);
        if (wheel == null) {
            throw new IllegalArgumentException("time wheel is not hosted locally: " + id);
        }
        return wheel;
    }

    @Override
    public Collection<TimeWheel> all() {
        return List.copyOf(wheels.values());
    }

    private static String requireId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("timeWheelId must not be blank");
        }
        return value;
    }
}
