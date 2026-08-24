package com.when.api.grpc;

import com.when.core.SinkConfig;
import com.when.core.SinkType;

/** Transport values after structural validation and conversion to common contracts. */
record ValidatedSubmit(
        long deliverAt,
        SinkType sinkType,
        SinkConfig sinkConfig,
        byte[] payload,
        String businessTag) {
}
