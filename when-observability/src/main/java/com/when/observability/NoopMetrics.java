package com.when.observability;

import java.util.function.Supplier;

enum NoopMetrics implements Metrics {
    INSTANCE;

    @Override
    public void incr(String name, String... tags) {
    }

    @Override
    public void observe(String name, double value, String... tags) {
    }

    @Override
    public void gauge(String name, Supplier<Number> value, String... tags) {
    }
}
