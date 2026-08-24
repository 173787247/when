package com.when.api.grpc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.ByteString;
import com.when.common.proto.HttpSinkConfig;
import com.when.common.proto.KafkaSinkConfig;
import com.when.common.proto.FileSinkConfig;
import com.when.common.proto.SinkConfig;
import com.when.common.proto.SinkType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SubmitRequestValidatorTest {
    private static final long NOW = 1_750_000_000_000L;
    private final SubmitRequestValidator validator = new SubmitRequestValidator(
            Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));

    @Test
    void acceptsExactlyOneFutureTimeAndResolvesRelativeDelay() {
        ValidatedSubmit absolute = validator.validate(httpRequest().setDeliverAt(NOW + 1).build());
        ValidatedSubmit relative = validator.validate(httpRequest().setDelaySeconds(30).build());

        assertEquals(NOW + 1, absolute.deliverAt());
        assertEquals(NOW + 30_000, relative.deliverAt());
    }

    @Test
    void rejectsMissingOrConflictingTimeFields() {
        assertInvalid(httpRequest().build());
        assertInvalid(httpRequest().setDeliverAt(NOW + 1).setDelaySeconds(1).build());
        assertInvalid(httpRequest().setDeliverAt(NOW).build());
        assertInvalid(httpRequest().setDeliverAt(NOW - 1).build());
    }

    @Test
    void enforcesDelayBoundaries() {
        assertInvalid(httpRequest().setDelaySeconds(-1).build());
        assertDoesNotThrow(() -> validator.validate(httpRequest().setDelaySeconds(1).build()));
        assertDoesNotThrow(() -> validator.validate(
                httpRequest().setDelaySeconds(SubmitRequestValidator.MAX_DELAY_SECONDS).build()));
        assertInvalid(httpRequest()
                .setDelaySeconds(SubmitRequestValidator.MAX_DELAY_SECONDS + 1)
                .build());
    }

    @Test
    void rejectsUnsupportedOrMismatchedSinkConfiguration() {
        assertInvalid(SubmitRequest.newBuilder()
                .setDelaySeconds(1)
                .setSinkType(SinkType.SINK_TYPE_UNSPECIFIED)
                .setSinkConfig(httpConfig())
                .build());
        assertInvalid(httpRequest()
                .setDelaySeconds(1)
                .setSinkConfig(SinkConfig.newBuilder().setKafka(KafkaSinkConfig.newBuilder()
                        .setBootstrapServers("broker:9092")
                        .setTopic("events")))
                .build());
        assertInvalid(SubmitRequest.newBuilder()
                .setDelaySeconds(1)
                .setSinkType(SinkType.HTTP)
                .build());
        assertInvalid(httpRequest()
                .setDelaySeconds(1)
                .setSinkConfig(SinkConfig.newBuilder().setHttp(HttpSinkConfig.getDefaultInstance()))
                .build());
    }

    @Test
    void validatesKafkaRequiredFields() {
        var base = SubmitRequest.newBuilder()
                .setDelaySeconds(1)
                .setSinkType(SinkType.KAFKA);
        assertInvalid(base.clone()
                .setSinkConfig(SinkConfig.newBuilder().setKafka(
                        KafkaSinkConfig.newBuilder().setTopic("events")))
                .build());
        assertInvalid(base.clone()
                .setSinkConfig(SinkConfig.newBuilder().setKafka(
                        KafkaSinkConfig.newBuilder().setBootstrapServers("broker:9092")))
                .build());
        assertDoesNotThrow(() -> validator.validate(base
                .setSinkConfig(SinkConfig.newBuilder().setKafka(KafkaSinkConfig.newBuilder()
                        .setBootstrapServers("broker:9092")
                        .setTopic("events")))
                .build()));
    }

    @Test
    void validatesFileRequiredPath() {
        var base = SubmitRequest.newBuilder()
                .setDelaySeconds(1)
                .setSinkType(SinkType.FILE);
        assertInvalid(base.clone()
                .setSinkConfig(SinkConfig.newBuilder().setFile(FileSinkConfig.getDefaultInstance()))
                .build());
        assertDoesNotThrow(() -> validator.validate(base
                .setSinkConfig(SinkConfig.newBuilder().setFile(FileSinkConfig.newBuilder()
                        .setPath("deliveries/message-1.bin")))
                .build()));
    }

    @Test
    void enforcesDecodedPayloadLimit() {
        assertDoesNotThrow(() -> validator.validate(httpRequest()
                .setDelaySeconds(1)
                .setPayload(ByteString.copyFrom(new byte[SubmitRequestValidator.MAX_PAYLOAD_BYTES]))
                .build()));
        assertInvalid(httpRequest()
                .setDelaySeconds(1)
                .setPayload(ByteString.copyFrom(
                        new byte[SubmitRequestValidator.MAX_PAYLOAD_BYTES + 1]))
                .build());
    }

    private void assertInvalid(SubmitRequest request) {
        assertThrows(RequestValidationException.class, () -> validator.validate(request));
    }

    private static SubmitRequest.Builder httpRequest() {
        return SubmitRequest.newBuilder()
                .setSinkType(SinkType.HTTP)
                .setSinkConfig(httpConfig());
    }

    private static SinkConfig httpConfig() {
        return SinkConfig.newBuilder()
                .setHttp(HttpSinkConfig.newBuilder()
                        .setUrl("https://example.internal/callback")
                        .setMethod("POST"))
                .build();
    }
}
