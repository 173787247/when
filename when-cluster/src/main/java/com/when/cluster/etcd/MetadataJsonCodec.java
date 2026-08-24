package com.when.cluster.etcd;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** JSON codec for the deliberately small etcd metadata values. */
public final class MetadataJsonCodec {
    private final ObjectMapper mapper;

    public MetadataJsonCodec() {
        this(new ObjectMapper());
    }

    MetadataJsonCodec(ObjectMapper mapper) {
        this.mapper = mapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public String encodeNode(NodeMetadata metadata) {
        return write(metadata);
    }

    public NodeMetadata decodeNode(String value) {
        return read(value, NodeMetadata.class);
    }

    public String encodeTimeWheel(TimeWheelMetadata metadata) {
        return write(metadata);
    }

    public TimeWheelMetadata decodeTimeWheel(String value) {
        return read(value, TimeWheelMetadata.class);
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("metadata cannot be encoded", e);
        }
    }

    private <T> T read(String value, Class<T> type) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("metadata value must not be blank");
        }
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("metadata cannot be decoded", e);
        }
    }
}
