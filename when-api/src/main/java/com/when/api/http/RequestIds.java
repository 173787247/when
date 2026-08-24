package com.when.api.http;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Request correlation identifiers are diagnostic only and never used for idempotency. */
public final class RequestIds {
    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = RequestIds.class.getName() + ".requestId";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private RequestIds() {
    }

    public static String normalize(String candidate) {
        return candidate != null && VALID.matcher(candidate).matches()
                ? candidate
                : "req_" + UUID.randomUUID().toString().replace("-", "");
    }

    public static String current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            Object value = request.getAttribute(ATTRIBUTE);
            if (value instanceof String requestId) {
                return requestId;
            }
        }
        return normalize(null);
    }
}
