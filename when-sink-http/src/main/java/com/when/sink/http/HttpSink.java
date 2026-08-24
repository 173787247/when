package com.when.sink.http;

import com.when.core.DeliveryResult;
import com.when.core.HttpSinkConfig;
import com.when.core.InvalidConfigException;
import com.when.core.Message;
import com.when.core.SinkConfig;
import com.when.core.SinkType;
import com.when.sink.spi.AttemptAwareSink;
import com.when.observability.TraceOperations;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** Bounded, redirect-free HTTP Sink with target-address validation. */
public final class HttpSink implements AttemptAwareSink {
    static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final int DEFAULT_TIMEOUT_MS = 5_000;
    private static final Pattern METHOD = Pattern.compile("[A-Z][A-Z0-9_-]{0,31}");
    private static final Set<String> RESERVED_HEADERS = Set.of(
            "x-when-message-id", "x-when-attempt-id", "x-when-trace-id", "traceparent",
            "tracestate", "content-length", "host");

    private final HttpClient client;
    private final HttpTargetPolicy targetPolicy;
    private final TraceOperations traces;

    /** Java-SPI constructor using production-safe environment settings. */
    public HttpSink() {
        this(newClient(), HttpTargetPolicy.fromEnvironment(), TraceOperations.noop());
    }

    public HttpSink(TraceOperations traces) {
        this(newClient(), HttpTargetPolicy.fromEnvironment(), traces);
    }

    public HttpSink(HttpClient client, HttpTargetPolicy targetPolicy) {
        this(client, targetPolicy, TraceOperations.noop());
    }

    public HttpSink(HttpClient client, HttpTargetPolicy targetPolicy, TraceOperations traces) {
        this.client = Objects.requireNonNull(client, "client");
        this.targetPolicy = Objects.requireNonNull(targetPolicy, "targetPolicy");
        this.traces = Objects.requireNonNull(traces, "traces");
    }

    @Override
    public SinkType type() {
        return SinkType.HTTP;
    }

    @Override
    public void validateConfig(SinkConfig value) throws InvalidConfigException {
        HttpSinkConfig config = requireConfig(value);
        targetPolicy.validate(config.url());
        normalizedMethod(config.method());
        timeout(config.timeoutMs());
        validateHeaders(config.headers());
    }

    @Override
    public DeliveryResult deliver(Message message, String attemptId) {
        Objects.requireNonNull(message, "message");
        long started = System.nanoTime();
        final HttpSinkConfig config;
        final URI uri;
        try {
            config = requireConfig(message.sinkConfig());
            uri = targetPolicy.validate(config.url());
            validateHeaders(config.headers());
        } catch (InvalidConfigException exception) {
            if (exception.getCause() instanceof java.net.UnknownHostException) {
                return DeliveryResult.retryableFailure(
                        "HTTP_CONNECTION", "HTTP host could not be resolved", elapsed(started));
            }
            return DeliveryResult.permanentFailure("HTTP_CONFIG", exception.getMessage(), elapsed(started));
        }

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(timeout(config.timeoutMs()))
                    .method(normalizedMethod(config.method()),
                            HttpRequest.BodyPublishers.ofByteArray(payload(message)))
                    .header("X-When-Message-Id", requireText(message.messageId(), "messageId"))
                    .header("X-When-Attempt-Id", requireText(attemptId, "attemptId"))
                    .header("X-When-Trace-Id", safeTrace(message.traceId()));
            config.headers().forEach(builder::header);
            traces.injectCurrentContext().forEach(builder::header);

            HttpResponse<InputStream> response = client.send(
                    builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            consumeBounded(response.body());
            int status = response.statusCode();
            long duration = elapsed(started);
            if (status >= 200 && status < 300) {
                return DeliveryResult.success(duration);
            }
            if (status >= 500 && status <= 599) {
                return DeliveryResult.retryableFailure(
                        "HTTP_5XX", "HTTP downstream returned status " + status, duration);
            }
            if (status == 408 || status == 429) {
                return DeliveryResult.retryableFailure(
                        "HTTP_" + status, "HTTP downstream returned status " + status, duration);
            }
            return DeliveryResult.permanentFailure(
                    status >= 400 && status <= 499 ? "HTTP_4XX" : "HTTP_STATUS",
                    "HTTP downstream returned status " + status,
                    duration);
        } catch (java.net.http.HttpTimeoutException exception) {
            return DeliveryResult.retryableFailure("HTTP_TIMEOUT", "HTTP delivery timed out", elapsed(started));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return DeliveryResult.retryableFailure("HTTP_INTERRUPTED", "HTTP delivery was interrupted", elapsed(started));
        } catch (IOException exception) {
            return DeliveryResult.retryableFailure("HTTP_CONNECTION", "HTTP connection failed", elapsed(started));
        } catch (IllegalArgumentException exception) {
            return DeliveryResult.permanentFailure("HTTP_CONFIG", "HTTP request configuration is invalid", elapsed(started));
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            String code = cause instanceof TimeoutException ? "HTTP_TIMEOUT" : "HTTP_CONNECTION";
            return DeliveryResult.retryableFailure(code, "HTTP delivery failed", elapsed(started));
        }
    }

    private static HttpClient newClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private static HttpSinkConfig requireConfig(SinkConfig value) {
        if (!(value instanceof HttpSinkConfig config)) {
            throw new InvalidConfigException("HTTP Sink requires HttpSinkConfig");
        }
        return config;
    }

    private static String normalizedMethod(String configured) {
        String method = configured == null || configured.isBlank()
                ? "POST"
                : configured.toUpperCase(Locale.ROOT);
        if (!METHOD.matcher(method).matches() || "CONNECT".equals(method) || "TRACE".equals(method)) {
            throw new InvalidConfigException("HTTP sink method is invalid");
        }
        return method;
    }

    private static Duration timeout(int configured) {
        int millis = configured == 0 ? DEFAULT_TIMEOUT_MS : configured;
        if (millis < 1 || millis > 300_000) {
            throw new InvalidConfigException("HTTP sink timeout must be between 1 and 300000 milliseconds");
        }
        return Duration.ofMillis(millis);
    }

    private static void validateHeaders(Map<String, String> headers) {
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String name = header.getKey();
            String value = header.getValue();
            if (name == null || name.isBlank() || value == null || containsLineBreak(value)) {
                throw new InvalidConfigException("HTTP sink header is invalid");
            }
            if (RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                throw new InvalidConfigException("HTTP sink header conflicts with a reserved header");
            }
        }
    }

    private static boolean containsLineBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    private static byte[] payload(Message message) {
        byte[] payload = message.payload();
        return payload == null ? new byte[0] : payload;
    }

    private static String safeTrace(String traceId) {
        return traceId == null || traceId.isBlank() ? "-" : traceId;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new InvalidConfigException(name + " must not be blank");
        }
        return value;
    }

    private static void consumeBounded(InputStream body) throws IOException {
        try (InputStream input = body) {
            input.readNBytes(MAX_RESPONSE_BYTES);
        }
    }

    private static long elapsed(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }
}
