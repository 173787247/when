package com.when.api.http;

import com.when.api.application.*;
import com.when.api.http.generated.BusinessApi;
import com.when.api.http.generated.model.*;
import com.when.core.HttpSinkConfig;
import com.when.core.KafkaSinkConfig;
import java.net.URI;
import java.time.Clock;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

/** Thin HTTP mapping onto the lesson 45 application boundary. */
@RestController
public final class BusinessHttpController implements BusinessApi {
    private static final long MIN_DELAY_MILLIS = 1_000L;
    private static final long MAX_DELAY_MILLIS = 30L * 24 * 60 * 60 * 1_000;
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    private final DelayMessageHandler handler;
    private final Clock clock;
    private final AdminIndexWriter adminIndex;

    public BusinessHttpController(DelayMessageHandler handler) {
        this(handler, AdminIndexWriter.noop(), Clock.systemUTC());
    }

    @Autowired
    public BusinessHttpController(DelayMessageHandler handler, AdminIndexWriter adminIndex) {
        this(handler, adminIndex, Clock.systemUTC());
    }

    BusinessHttpController(DelayMessageHandler handler, AdminIndexWriter adminIndex, Clock clock) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.adminIndex = Objects.requireNonNull(adminIndex, "adminIndex");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ResponseEntity<ApiEnvelope<SubmitMessageData>> submitMessage(
            String ignoredRequestId, SubmitMessageRequest request) {
        long createdAt = clock.millis();
        SubmitCommand command = toCommand(request, createdAt);
        SubmitResult result = handler.submit(command);
        adminIndex.index(result.messageId(), command.deliverAt());
        SubmitMessageData data = new SubmitMessageData(
                result.messageId(), status(result.status()), createdAt);
        String requestId = RequestIds.current();
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(RequestIds.HEADER, requestId)
                .header(HttpHeaders.LOCATION, "/api/v1/messages/" + result.messageId())
                .body(ApiEnvelope.ok(requestId, data));
    }

    @Override
    public ResponseEntity<ApiEnvelope<MessageSummary>> queryMessage(String ignoredRequestId, String id) {
        String requestId = RequestIds.current();
        return ResponseEntity.ok().header(RequestIds.HEADER, requestId)
                .body(ApiEnvelope.ok(requestId, summary(handler.query(id))));
    }

    @Override
    public ResponseEntity<ApiEnvelope<CancelMessageData>> cancelMessage(String ignoredRequestId, String id) {
        long cancelledAt = clock.millis();
        try {
            CancelResult result = handler.cancel(id);
            String requestId = RequestIds.current();
            return ResponseEntity.ok().header(RequestIds.HEADER, requestId).body(ApiEnvelope.ok(requestId,
                    new CancelMessageData(result.messageId(), status(result.status()), cancelledAt)));
        } catch (CancellationRejectedException rejected) {
            MessageView existing = handler.query(id);
            if (existing.status() == com.when.core.MessageStatus.CANCELLED) {
                String requestId = RequestIds.current();
                return ResponseEntity.ok().header(RequestIds.HEADER, requestId).body(ApiEnvelope.ok(requestId,
                        new CancelMessageData(existing.messageId(), MessageStatus.CANCELLED, cancelledAt)));
            }
            throw rejected;
        }
    }

    private SubmitCommand toCommand(SubmitMessageRequest request, long now) {
        Objects.requireNonNull(request, "request");
        if ((request.deliverAt() == null) == (request.delaySeconds() == null)) {
            throw new IllegalArgumentException("exactly one delivery time is required");
        }
        long deliverAt;
        try {
            deliverAt = request.deliverAt() != null
                    ? request.deliverAt()
                    : Math.addExact(now, Math.multiplyExact(request.delaySeconds(), 1_000L));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("delivery time is outside the supported range", overflow);
        }
        long delay = deliverAt - now;
        if (delay < MIN_DELAY_MILLIS || delay > MAX_DELAY_MILLIS) {
            throw new IllegalArgumentException("delivery time must be between 1 second and 30 days");
        }
        byte[] payload = decodePayload(request.payload());
        return new SubmitCommand(
                deliverAt,
                com.when.core.SinkType.valueOf(request.sinkType().name()),
                sinkConfig(request),
                payload,
                request.businessTag());
    }

    private static com.when.core.SinkConfig sinkConfig(SubmitMessageRequest request) {
        SinkConfig config = request.sinkConfig();
        if (request.sinkType() == SinkType.HTTP && config.http() != null && config.kafka() == null) {
            URI.create(config.http().url());
            return new HttpSinkConfig(
                    config.http().url(),
                    defaultText(config.http().method(), "POST").toUpperCase(Locale.ROOT),
                    defaultMap(config.http().headers()),
                    config.http().timeoutMs() == null ? 5_000 : config.http().timeoutMs());
        }
        if (request.sinkType() == SinkType.KAFKA && config.kafka() != null && config.http() == null) {
            return new KafkaSinkConfig(
                    config.kafka().bootstrapServers(),
                    config.kafka().topic(),
                    config.kafka().key(),
                    defaultMap(config.kafka().headers()));
        }
        throw new IllegalArgumentException("sink_type and sink_config must match");
    }

    private static byte[] decodePayload(String payload) {
        if (payload == null || payload.isEmpty()) {
            return new byte[0];
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("payload must be base64", invalid);
        }
        if (decoded.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("payload exceeds 64KB");
        }
        return decoded;
    }

    public static MessageSummary summary(MessageView view) {
        return new MessageSummary(
                view.messageId(), status(view.status()), view.createdAt(), view.deliverAt(),
                view.deliveredAt(), SinkType.valueOf(view.sinkType().name()), view.businessTag(),
                view.retryCount(), sanitize(view.lastError(), 512));
    }

    private static MessageStatus status(com.when.core.MessageStatus status) {
        return MessageStatus.valueOf(status.name());
    }

    private static String sanitize(String value, int maxLength) {
        if (value == null) return null;
        String clean = value.replaceAll("[\\r\\n\\t]", " ");
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength);
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Map<String, String> defaultMap(Map<String, String> value) {
        return value == null ? Map.of() : value;
    }
}
