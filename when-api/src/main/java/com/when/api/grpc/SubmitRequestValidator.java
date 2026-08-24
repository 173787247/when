package com.when.api.grpc;

import com.when.common.proto.SinkConfig.ConfigCase;
import com.when.core.HttpSinkConfig;
import com.when.core.KafkaSinkConfig;
import com.when.core.SinkConfig;
import com.when.core.SinkType;
import java.time.Clock;
import java.util.Objects;

/** Performs only protocol-level submit validation; runtime Sink validation remains in the Sink module. */
public final class SubmitRequestValidator {
    public static final int MAX_DELAY_SECONDS = 2_592_000;
    public static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    private final Clock clock;

    public SubmitRequestValidator(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    ValidatedSubmit validate(SubmitRequest request) {
        Objects.requireNonNull(request, "request");
        boolean hasDeliverAt = request.getDeliverAt() != 0;
        boolean hasDelay = request.getDelaySeconds() != 0;
        if (hasDeliverAt == hasDelay) {
            throw new RequestValidationException(
                    "exactly one of deliver_at and delay_seconds must be provided");
        }

        long deliverAt;
        if (hasDeliverAt) {
            if (request.getDeliverAt() <= clock.millis()) {
                throw new RequestValidationException("deliver_at must be in the future");
            }
            deliverAt = request.getDeliverAt();
        } else {
            if (request.getDelaySeconds() < 1 || request.getDelaySeconds() > MAX_DELAY_SECONDS) {
                throw new RequestValidationException(
                        "delay_seconds must be between 1 and 2592000");
            }
            deliverAt = clock.millis() + request.getDelaySeconds() * 1_000L;
        }

        if (request.getPayload().size() > MAX_PAYLOAD_BYTES) {
            throw new RequestValidationException("payload must not exceed 65536 bytes");
        }
        if (!request.hasSinkConfig()) {
            throw new RequestValidationException("sink_config is required");
        }

        SinkType sinkType;
        SinkConfig sinkConfig;
        switch (request.getSinkType()) {
            case HTTP -> {
                requireConfigCase(request, ConfigCase.HTTP);
                if (request.getSinkConfig().getHttp().getUrl().isBlank()) {
                    throw new RequestValidationException("sink_config.http.url is required");
                }
                sinkType = SinkType.HTTP;
                var config = request.getSinkConfig().getHttp();
                sinkConfig = new HttpSinkConfig(
                        config.getUrl(), config.getMethod(), config.getHeadersMap(), config.getTimeoutMs());
            }
            case KAFKA -> {
                requireConfigCase(request, ConfigCase.KAFKA);
                var config = request.getSinkConfig().getKafka();
                if (config.getBootstrapServers().isBlank()) {
                    throw new RequestValidationException(
                            "sink_config.kafka.bootstrap_servers is required");
                }
                if (config.getTopic().isBlank()) {
                    throw new RequestValidationException("sink_config.kafka.topic is required");
                }
                sinkType = SinkType.KAFKA;
                sinkConfig = new KafkaSinkConfig(
                        config.getBootstrapServers(),
                        config.getTopic(),
                        config.getKey(),
                        config.getHeadersMap());
            }
            case SINK_TYPE_UNSPECIFIED, UNRECOGNIZED ->
                    throw new RequestValidationException("sink_type must be HTTP or KAFKA");
            default -> throw new RequestValidationException("sink_type must be HTTP or KAFKA");
        }

        return new ValidatedSubmit(
                deliverAt,
                sinkType,
                sinkConfig,
                request.getPayload().toByteArray(),
                request.getBusinessTag());
    }

    private static void requireConfigCase(SubmitRequest request, ConfigCase expected) {
        if (request.getSinkConfig().getConfigCase() != expected) {
            throw new RequestValidationException("sink_config must match sink_type");
        }
    }
}
