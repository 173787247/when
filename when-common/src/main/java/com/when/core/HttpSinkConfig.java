package com.when.core;

import java.util.Map;

/** Configuration for an HTTP delivery target. */
public record HttpSinkConfig(String url, String method, Map<String, String> headers, int timeoutMs)
        implements SinkConfig {

    public HttpSinkConfig {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    @Override
    public SinkType type() {
        return SinkType.HTTP;
    }
}
