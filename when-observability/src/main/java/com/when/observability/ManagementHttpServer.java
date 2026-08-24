package com.when.observability;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small isolated management listener for metrics, liveness, readiness, and guarded log levels. */
public final class ManagementHttpServer implements AutoCloseable {
    private static final Pattern LEVEL = Pattern.compile(
            "\\\"configuredLevel\\\"\\s*:\\s*\\\"(TRACE|DEBUG|INFO|WARN|ERROR|OFF)\\\"",
            Pattern.CASE_INSENSITIVE);

    private final HttpServer server;
    private final Metrics metrics;
    private final ReadinessManager readiness;
    private final ManagementConfig config;
    private final ExecutorService executor;

    public ManagementHttpServer(
            ManagementConfig config, Metrics metrics, ReadinessManager readiness) throws IOException {
        this.config = Objects.requireNonNull(config, "config");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        this.server.createContext("/metrics", this::metrics);
        this.server.createContext("/health", this::health);
        this.server.createContext("/ready", this::ready);
        this.server.createContext("/actuator/loggers", this::loggerLevel);
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "when-management-http");
            thread.setDaemon(true);
            return thread;
        });
        this.server.setExecutor(executor);
    }

    public ManagementHttpServer start() {
        server.start();
        return this;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void metrics(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) {
            return;
        }
        send(exchange, 200, MicrometerMetrics.OPEN_METRICS_CONTENT_TYPE, metrics.scrape());
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) {
            return;
        }
        send(exchange, 200, "application/json", "{\"status\":\"UP\"}");
    }

    private void ready(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) {
            return;
        }
        ReadinessSnapshot snapshot = readiness.snapshot();
        String reasons = snapshot.reasons().stream()
                .map(reason -> "\"" + reason.name() + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        String body = "{\"status\":\"" + (snapshot.ready() ? "UP" : "DOWN")
                + "\",\"reasons\":[" + reasons + "]}";
        send(exchange, snapshot.ready() ? 200 : 503, "application/json", body);
    }

    private void loggerLevel(HttpExchange exchange) throws IOException {
        if (!method(exchange, "POST")) {
            return;
        }
        if (!config.runtimeLogLevelEnabled() || !isLoopback(exchange)) {
            send(exchange, 403, "application/json", "{\"error\":\"FORBIDDEN\"}");
            return;
        }
        String prefix = "/actuator/loggers/";
        String path = exchange.getRequestURI().getPath();
        if (!path.startsWith(prefix) || path.length() == prefix.length()) {
            send(exchange, 404, "application/json", "{\"error\":\"NOT_FOUND\"}");
            return;
        }
        String loggerName = path.substring(prefix.length());
        if (!loggerName.matches("[A-Za-z0-9_.-]{1,128}")) {
            send(exchange, 400, "application/json", "{\"error\":\"INVALID_LOGGER\"}");
            return;
        }
        String body = new String(exchange.getRequestBody().readNBytes(1024), StandardCharsets.UTF_8);
        Matcher matcher = LEVEL.matcher(body);
        if (!matcher.find()) {
            send(exchange, 400, "application/json", "{\"error\":\"INVALID_LEVEL\"}");
            return;
        }
        setLogLevel(loggerName, matcher.group(1).toUpperCase(Locale.ROOT));
        send(exchange, 204, "application/json", "");
    }

    private static void setLogLevel(String loggerName, String level) {
        java.util.logging.Level julLevel = switch (level) {
            case "TRACE" -> java.util.logging.Level.FINEST;
            case "DEBUG" -> java.util.logging.Level.FINE;
            case "WARN" -> java.util.logging.Level.WARNING;
            case "ERROR" -> java.util.logging.Level.SEVERE;
            case "OFF" -> java.util.logging.Level.OFF;
            default -> java.util.logging.Level.INFO;
        };
        java.util.logging.Logger.getLogger(loggerName).setLevel(julLevel);
        try {
            Object factory = org.slf4j.LoggerFactory.getILoggerFactory();
            Class<?> contextClass = Class.forName("ch.qos.logback.classic.LoggerContext");
            Class<?> levelClass = Class.forName("ch.qos.logback.classic.Level");
            if (contextClass.isInstance(factory)) {
                Object logger = contextClass.getMethod("getLogger", String.class).invoke(factory, loggerName);
                Object configured = levelClass.getMethod("toLevel", String.class).invoke(null, level);
                logger.getClass().getMethod("setLevel", levelClass).invoke(logger, configured);
            }
        } catch (ReflectiveOperationException ignored) {
            // JUL has already been updated; Logback is optional outside the packaged application.
        }
    }

    private static boolean isLoopback(HttpExchange exchange) {
        InetAddress address = exchange.getRemoteAddress().getAddress();
        return address != null && address.isLoopbackAddress();
    }

    private static boolean method(HttpExchange exchange, String allowed) throws IOException {
        if (allowed.equals(exchange.getRequestMethod())) {
            return true;
        }
        exchange.getResponseHeaders().set("Allow", allowed);
        send(exchange, 405, "application/json", "{\"error\":\"METHOD_NOT_ALLOWED\"}");
        return false;
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        if (status != 204) {
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        } else {
            exchange.close();
        }
    }
}
