package com.when.ingress.application;

import io.grpc.Context;

/** Request-scoped identity supplied only by the authenticated internal forwarding path. */
public final class ForwardingContext {
    static final Context.Key<String> MESSAGE_ID = Context.key("when-forwarded-message-id");
    static final Context.Key<String> TRACE_ID = Context.key("when-forwarded-trace-id");
    static final Context.Key<String> TIME_WHEEL_ID = Context.key("when-forwarded-time-wheel-id");

    private ForwardingContext() {
    }

    public static Context withIdentity(
            Context base, String messageId, String traceId, String timeWheelId) {
        return base.withValue(MESSAGE_ID, messageId)
                .withValue(TRACE_ID, traceId)
                .withValue(TIME_WHEEL_ID, timeWheelId);
    }

    static RoutedIdentity current() {
        String messageId = MESSAGE_ID.get();
        String traceId = TRACE_ID.get();
        String timeWheelId = TIME_WHEEL_ID.get();
        if (messageId == null && traceId == null && timeWheelId == null) {
            return null;
        }
        if (isBlank(messageId) || isBlank(traceId) || isBlank(timeWheelId)) {
            throw new IngressValidationException("forwarded routing identity is incomplete");
        }
        return new RoutedIdentity(messageId, traceId, timeWheelId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    record RoutedIdentity(String messageId, String traceId, String timeWheelId) {
    }
}
