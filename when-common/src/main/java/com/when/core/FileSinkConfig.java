package com.when.core;

/** Configuration for a local file delivery target, relative to the node's configured base directory. */
public record FileSinkConfig(String path) implements SinkConfig {
    @Override
    public SinkType type() {
        return SinkType.FILE;
    }
}
