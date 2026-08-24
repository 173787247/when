package com.when.api.http;

import static org.junit.jupiter.api.Assertions.*;

import com.when.api.application.*;
import com.when.api.http.generated.model.*;
import com.when.core.MessageStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BusinessHttpControllerTest {
    private static final long NOW = 1_800_000_000_000L;

    @Test
    void submitMapsContractPersistsThroughHandlerAndIndexesAfterSuccess() {
        AtomicReference<SubmitCommand> captured = new AtomicReference<>();
        AtomicReference<String> indexed = new AtomicReference<>();
        DelayMessageHandler handler = new StubHandler() {
            @Override public SubmitResult submit(SubmitCommand command) {
                captured.set(command);
                return new SubmitResult("msg-1", MessageStatus.PENDING, command.deliverAt());
            }
        };
        BusinessHttpController controller = new BusinessHttpController(
                handler, (id, deliverAt) -> indexed.set(id + ":" + deliverAt), fixedClock());
        SubmitMessageRequest request = new SubmitMessageRequest(
                null, 5L, SinkType.HTTP,
                new SinkConfig(new HttpSinkConfig("https://example.test/callback", null, Map.of(), null), null),
                Base64.getEncoder().encodeToString("safe".getBytes()), "orders");

        var response = controller.submitMessage(null, request);

        assertEquals(201, response.getStatusCode().value());
        assertEquals("/api/v1/messages/msg-1", response.getHeaders().getLocation().toString());
        assertEquals("msg-1", response.getBody().data().messageId());
        assertEquals(NOW + 5_000, captured.get().deliverAt());
        assertArrayEquals("safe".getBytes(), captured.get().payload());
        assertEquals("msg-1:" + (NOW + 5_000), indexed.get());
        assertEquals(response.getHeaders().getFirst(RequestIds.HEADER), response.getBody().requestId());
    }

    @Test
    void submitRejectsAmbiguousTimeAndMismatchedStrongSinkConfig() {
        BusinessHttpController controller = new BusinessHttpController(new StubHandler(), AdminIndexWriter.noop(), fixedClock());
        assertThrows(IllegalArgumentException.class, () -> controller.submitMessage(null,
                new SubmitMessageRequest(NOW + 5_000, 5L, SinkType.HTTP,
                        new SinkConfig(new HttpSinkConfig("https://example.test", "POST", Map.of(), 1000), null),
                        null, null)));
        assertThrows(IllegalArgumentException.class, () -> controller.submitMessage(null,
                new SubmitMessageRequest(null, 5L, SinkType.HTTP,
                        new SinkConfig(null, new KafkaSinkConfig("broker:9092", "topic", null, Map.of())),
                        null, null)));
    }

    @Test
    void repeatedCancelOfAlreadyCancelledMessageIsIdempotent() {
        DelayMessageHandler handler = new StubHandler() {
            @Override public CancelResult cancel(String messageId) { throw new CancellationRejectedException(); }
            @Override public MessageView query(String messageId) {
                return new MessageView(messageId, MessageStatus.CANCELLED, NOW, NOW + 5_000, 0, 0, null,
                        com.when.core.SinkType.HTTP, null);
            }
        };
        var response = new BusinessHttpController(handler, AdminIndexWriter.noop(), fixedClock())
                .cancelMessage(null, "msg-1");
        assertEquals(com.when.api.http.generated.model.MessageStatus.CANCELLED,
                response.getBody().data().status());
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
    }

    private static class StubHandler implements DelayMessageHandler {
        @Override public SubmitResult submit(SubmitCommand command) { throw new UnsupportedOperationException(); }
        @Override public MessageView query(String messageId) { throw new MessageNotFoundException(messageId); }
        @Override public CancelResult cancel(String messageId) { throw new UnsupportedOperationException(); }
    }
}
