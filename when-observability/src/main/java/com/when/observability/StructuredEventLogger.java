package com.when.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

/** Emits stable structured fields; payload and Sink configuration are not accepted by this API. */
public final class StructuredEventLogger {
    private final Logger logger;
    private final String service;
    private final String nodeId;

    public StructuredEventLogger(Class<?> owner, String service, String nodeId) {
        this(LoggerFactory.getLogger(Objects.requireNonNull(owner, "owner")), service, nodeId);
    }

    StructuredEventLogger(Logger logger, String service, String nodeId) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.service = requireText(service, "service");
        this.nodeId = requireText(nodeId, "nodeId");
    }

    public void info(LogEvent event, String message, Map<String, ?> fields) {
        log(logger.atInfo(), event, ErrorCode.NONE, message, fields);
    }

    public void warn(LogEvent event, ErrorCode errorCode, String message, Map<String, ?> fields) {
        log(logger.atWarn(), event, errorCode, message, fields);
    }

    private void log(
            LoggingEventBuilder builder,
            LogEvent event,
            ErrorCode errorCode,
            String message,
            Map<String, ?> fields) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(errorCode, "errorCode");
        builder.addKeyValue("service", service)
                .addKeyValue("node_id", nodeId)
                .addKeyValue("event", event.value())
                .addKeyValue("error_code", errorCode.name());
        safeFields(fields).forEach(builder::addKeyValue);
        builder.log(requireText(message, "message"));
    }

    private static Map<String, Object> safeFields(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null || !allowedField(key) || value == null) {
                return;
            }
            String rendered = value.toString().replace('\r', ' ').replace('\n', ' ');
            result.put(key, rendered.length() <= 512 ? rendered : rendered.substring(0, 512));
        });
        return Map.copyOf(result);
    }

    private static boolean allowedField(String name) {
        return switch (name) {
            case "message_id", "tw_id", "sink_type", "operation", "status", "duration_ms",
                    "assignment_version", "result", "reason" -> true;
            default -> false;
        };
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
