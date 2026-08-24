package com.when.sink.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.when.core.DeliveryResult;
import com.when.core.HttpSinkConfig;
import com.when.core.InvalidConfigException;
import com.when.core.Message;
import com.when.core.MessageStatus;
import com.when.core.SinkType;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class HttpSinkTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsPayloadAndIdentityHeadersAndClassifiesStatuses() throws Exception {
        byte[] payload = "delayed-payload".getBytes(StandardCharsets.UTF_8);
        AtomicReference<byte[]> received = new AtomicReference<>();
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (SocketException exception) {
            Assumptions.assumeTrue(false, "local callback port binding is unavailable");
            return;
        }
        server.createContext("/ok", exchange -> respond(exchange, 204, received));
        server.createContext("/retry", exchange -> respond(exchange, 503, received));
        server.createContext("/bad", exchange -> respond(exchange, 400, received));
        server.start();
        HttpSink sink = new HttpSink(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                HttpTargetPolicy.developmentLoopback());

        DeliveryResult success = sink.deliver(message("/ok", payload), "attempt-one");
        DeliveryResult retry = sink.deliver(message("/retry", payload), "attempt-two");
        DeliveryResult permanent = sink.deliver(message("/bad", payload), "attempt-three");

        assertTrue(success.success());
        assertTrue(retry.retryable());
        assertEquals("HTTP_5XX", retry.errorCode());
        assertFalse(permanent.retryable());
        assertEquals("HTTP_4XX", permanent.errorCode());
        assertArrayEquals(payload, received.get());
    }

    @Test
    void productionPolicyRejectsLocalTargets() {
        HttpSink sink = new HttpSink(HttpClient.newHttpClient(), HttpTargetPolicy.production());
        assertThrows(InvalidConfigException.class,
                () -> sink.validateConfig(new HttpSinkConfig(
                        "http://127.0.0.1/callback", "POST", Map.of(), 1_000)));
        assertThrows(InvalidConfigException.class,
                () -> sink.validateConfig(new HttpSinkConfig(
                        "http://169.254.10.20/callback", "POST", Map.of(), 1_000)));
    }

    private Message message(String path, byte[] payload) {
        long now = System.currentTimeMillis();
        return new Message(
                "message-1", now, now, "tw-0", SinkType.HTTP,
                new HttpSinkConfig(
                        "http://127.0.0.1:" + server.getAddress().getPort() + path,
                        "POST", Map.of("Content-Type", "application/octet-stream"), 2_000),
                payload, null, MessageStatus.DELIVERING, 0, now, 0, null, "trace-1");
    }

    private static void respond(
            HttpExchange exchange, int status, AtomicReference<byte[]> received) throws IOException {
        received.set(exchange.getRequestBody().readAllBytes());
        assertEquals("message-1", exchange.getRequestHeaders().getFirst("X-When-Message-Id"));
        assertTrue(exchange.getRequestHeaders().getFirst("X-When-Attempt-Id").startsWith("attempt-"));
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }
}
