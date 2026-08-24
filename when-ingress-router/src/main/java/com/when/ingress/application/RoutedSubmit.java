package com.when.ingress.application;

import com.when.api.application.SubmitCommand;
import java.util.Objects;

/** Submit identity and route preserved across an internal node hop. */
public record RoutedSubmit(
        String messageId,
        String traceId,
        String timeWheelId,
        SubmitCommand command) {

    public RoutedSubmit {
        requireText(messageId, "messageId");
        requireText(traceId, "traceId");
        requireText(timeWheelId, "timeWheelId");
        Objects.requireNonNull(command, "command");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
