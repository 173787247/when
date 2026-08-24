package com.when.api.http;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** Safe, stable HTTP error raised by protocol mapping and admin services. */
public final class HttpApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> data;

    public HttpApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public HttpApiException(HttpStatus status, String code, String message, Map<String, Object> data) {
        super(message);
        this.status = status;
        this.code = code;
        this.data = data == null ? Map.of() : Map.copyOf(data);
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public Map<String, Object> data() { return data; }
}
