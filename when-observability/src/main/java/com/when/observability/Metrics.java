package com.when.observability;

import java.util.function.Supplier;

/** Stable, cardinality-controlled metrics boundary used by production modules. */
public interface Metrics {
    void incr(String name, String... tags);

    void observe(String name, double value, String... tags);

    void gauge(String name, Supplier<Number> value, String... tags);

    default String scrape() {
        return "";
    }

    static Metrics noop() {
        return NoopMetrics.INSTANCE;
    }
}
