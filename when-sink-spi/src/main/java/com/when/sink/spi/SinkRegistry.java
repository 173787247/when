package com.when.sink.spi;

import com.when.core.Sink;
import com.when.core.SinkType;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;

/** Immutable SinkType-to-plugin registry populated explicitly or through Java SPI. */
public final class SinkRegistry implements AutoCloseable {
    private final Map<SinkType, Sink> sinks;

    public SinkRegistry(Collection<? extends Sink> sinks) {
        Objects.requireNonNull(sinks, "sinks");
        EnumMap<SinkType, Sink> byType = new EnumMap<>(SinkType.class);
        for (Sink sink : sinks) {
            Objects.requireNonNull(sink, "sink");
            Sink previous = byType.putIfAbsent(
                    Objects.requireNonNull(sink.type(), "sink.type()"), sink);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate Sink plugin for " + sink.type());
            }
        }
        this.sinks = Map.copyOf(byType);
    }

    public static SinkRegistry load() {
        return load(Thread.currentThread().getContextClassLoader());
    }

    public static SinkRegistry load(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader");
        List<Sink> loaded = ServiceLoader.load(Sink.class, classLoader).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        return new SinkRegistry(loaded);
    }

    public Sink require(SinkType type) {
        Sink sink = sinks.get(Objects.requireNonNull(type, "type"));
        if (sink == null) {
            throw new IllegalArgumentException("no Sink plugin registered for " + type);
        }
        return sink;
    }

    public Map<SinkType, Sink> asMap() {
        return sinks;
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (Sink sink : sinks.values()) {
            if (sink instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception exception) {
                    if (failure == null) {
                        failure = new IllegalStateException("failed to close Sink plugin", exception);
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
