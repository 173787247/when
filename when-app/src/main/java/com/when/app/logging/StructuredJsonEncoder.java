package com.when.app.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.EncoderBase;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.event.KeyValuePair;

/** Dependency-free JSON encoder that gives legacy JUL/SLF4J records the same required fields. */
public final class StructuredJsonEncoder extends EncoderBase<ILoggingEvent> {
    private static final Set<String> OPTIONAL_FIELDS = Set.of(
            "trace_id",
            "span_id",
            "message_id",
            "tw_id",
            "sink_type",
            "operation",
            "status",
            "duration_ms",
            "assignment_version",
            "result",
            "reason",
            "event",
            "error_code",
            "service",
            "node_id");

    @Override
    public byte[] encode(ILoggingEvent event) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(event.getTimeStamp())));
        fields.put("level", event.getLevel().levelStr);
        fields.put("service", "when");
        fields.put("node_id", environment("WHEN_NODE_ID", "unknown"));
        fields.put("logger", event.getLoggerName());
        fields.put("event", "application_log");
        fields.put("error_code", "NONE");
        fields.put("message", event.getFormattedMessage());
        event.getMDCPropertyMap().forEach((key, value) -> putBounded(fields, key, value));
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs != null) {
            for (KeyValuePair pair : pairs) {
                putBounded(fields, pair.key, pair.value == null ? null : pair.value.toString());
            }
        }
        StringBuilder json = new StringBuilder(256).append('{');
        boolean first = true;
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            quote(json, field.getKey()).append(':');
            quote(json, field.getValue());
        }
        json.append('}').append(System.lineSeparator());
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] headerBytes() {
        return null;
    }

    @Override
    public byte[] footerBytes() {
        return null;
    }

    private static void putBounded(Map<String, String> fields, String key, String value) {
        if (key == null || !OPTIONAL_FIELDS.contains(key) || value == null) {
            return;
        }
        String safe = value.length() <= 512 ? value : value.substring(0, 512);
        fields.put(key, safe);
    }

    private static StringBuilder quote(StringBuilder target, String value) {
        target.append('"');
        String text = value == null ? "" : value;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            switch (current) {
                case '"' -> target.append("\\\"");
                case '\\' -> target.append("\\\\");
                case '\b' -> target.append("\\b");
                case '\f' -> target.append("\\f");
                case '\n' -> target.append("\\n");
                case '\r' -> target.append("\\r");
                case '\t' -> target.append("\\t");
                default -> {
                    if (current < 0x20) {
                        target.append(String.format("\\u%04x", (int) current));
                    } else {
                        target.append(current);
                    }
                }
            }
        }
        return target.append('"');
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
