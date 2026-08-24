package com.when.api.application;

import com.when.core.SinkConfig;
import com.when.core.SinkType;

/** Validated submit input passed to the application layer. */
public record SubmitCommand(
        long deliverAt,
        SinkType sinkType,
        SinkConfig sinkConfig,
        byte[] payload,
        String businessTag) {

    public SubmitCommand {
        payload = payload == null ? new byte[0] : payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
