package com.when.plugin.storage.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.when.core.HttpSinkConfig;
import com.when.core.KafkaSinkConfig;
import com.when.core.FileSinkConfig;
import com.when.core.Message;
import com.when.core.MessageStatus;
import com.when.core.SinkConfig;
import com.when.core.SinkType;
import java.util.Map;

final class MessageJsonCodec {
    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    String encode(Message message) {
        try {
            return mapper.writeValueAsString(StoredMessage.from(message));
        } catch (JsonProcessingException exception) {
            throw new RedisStorageException("Unable to serialize message " + message.messageId(), exception);
        }
    }

    Message decode(String json) {
        try {
            return mapper.readValue(json, StoredMessage.class).toMessage();
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new RedisStorageException("Unable to deserialize stored message", exception);
        }
    }

    private record StoredMessage(
            String messageId,
            long createdAt,
            long deliverAt,
            String timeWheelId,
            SinkType sinkType,
            HttpSinkData httpSink,
            KafkaSinkData kafkaSink,
            FileSinkData fileSink,
            byte[] payload,
            String businessTag,
            MessageStatus status,
            int retryCount,
            long nextAttemptAt,
            long deliveredAt,
            String lastError,
            String traceId) {

        private static StoredMessage from(Message message) {
            HttpSinkData http = null;
            KafkaSinkData kafka = null;
            FileSinkData file = null;
            if (message.sinkConfig() instanceof HttpSinkConfig config) {
                http = new HttpSinkData(config.url(), config.method(), config.headers(), config.timeoutMs());
            } else if (message.sinkConfig() instanceof KafkaSinkConfig config) {
                kafka = new KafkaSinkData(
                        config.bootstrapServers(), config.topic(), config.key(), config.headers());
            } else if (message.sinkConfig() instanceof FileSinkConfig config) {
                file = new FileSinkData(config.path());
            } else {
                throw new IllegalArgumentException("Unsupported sink configuration");
            }
            return new StoredMessage(
                    message.messageId(),
                    message.createdAt(),
                    message.deliverAt(),
                    message.timeWheelId(),
                    message.sinkType(),
                    http,
                    kafka,
                    file,
                    message.payload(),
                    message.businessTag(),
                    message.status(),
                    message.retryCount(),
                    message.nextAttemptAt(),
                    message.deliveredAt(),
                    message.lastError(),
                    message.traceId());
        }

        private Message toMessage() {
            SinkConfig config = switch (sinkType) {
                case HTTP -> {
                    if (httpSink == null || kafkaSink != null || fileSink != null) {
                        throw new IllegalArgumentException("Stored HTTP sink configuration is invalid");
                    }
                    yield new HttpSinkConfig(
                            httpSink.url(), httpSink.method(), httpSink.headers(), httpSink.timeoutMs());
                }
                case KAFKA -> {
                    if (kafkaSink == null || httpSink != null || fileSink != null) {
                        throw new IllegalArgumentException("Stored Kafka sink configuration is invalid");
                    }
                    yield new KafkaSinkConfig(
                            kafkaSink.bootstrapServers(), kafkaSink.topic(), kafkaSink.key(), kafkaSink.headers());
                }
                case FILE -> {
                    if (fileSink == null || httpSink != null || kafkaSink != null) {
                        throw new IllegalArgumentException("Stored file sink configuration is invalid");
                    }
                    yield new FileSinkConfig(fileSink.path());
                }
            };
            return new Message(
                    messageId,
                    createdAt,
                    deliverAt,
                    timeWheelId,
                    sinkType,
                    config,
                    payload,
                    businessTag,
                    status,
                    retryCount,
                    nextAttemptAt,
                    deliveredAt,
                    lastError,
                    traceId);
        }
    }

    private record HttpSinkData(String url, String method, Map<String, String> headers, int timeoutMs) {
        private HttpSinkData {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    private record KafkaSinkData(
            String bootstrapServers, String topic, String key, Map<String, String> headers) {
        private KafkaSinkData {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    private record FileSinkData(String path) {
    }
}
