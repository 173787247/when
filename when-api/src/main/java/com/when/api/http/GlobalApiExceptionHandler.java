package com.when.api.http;

import com.when.api.application.CancellationRejectedException;
import com.when.api.application.MessageNotFoundException;
import com.when.api.http.generated.model.ApiEnvelope;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/** The only transport-level exception conversion point for business and admin endpoints. */
@RestControllerAdvice
public final class GlobalApiExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalApiExceptionHandler.class);

    @ExceptionHandler(HttpApiException.class)
    ResponseEntity<ApiEnvelope<Map<String, Object>>> api(HttpApiException exception) {
        return response(exception.status(), exception.code(), exception.getMessage(), exception.data());
    }

    @ExceptionHandler(MessageNotFoundException.class)
    ResponseEntity<ApiEnvelope<Map<String, Object>>> notFound(MessageNotFoundException ignored) {
        return response(HttpStatus.NOT_FOUND, "MESSAGE_NOT_FOUND", "message not found", null);
    }

    @ExceptionHandler(CancellationRejectedException.class)
    ResponseEntity<ApiEnvelope<Map<String, Object>>> conflict(CancellationRejectedException ignored) {
        return response(HttpStatus.CONFLICT, "MESSAGE_STATE_CONFLICT", "message cannot be cancelled", null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiEnvelope<Map<String, Object>>> invalidJson(HttpMessageNotReadableException ignored) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_JSON", "request JSON is invalid", null);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
            MissingRequestHeaderException.class, IllegalArgumentException.class})
    ResponseEntity<ApiEnvelope<Map<String, Object>>> invalidArgument(Exception ignored) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "request argument is invalid", null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiEnvelope<Map<String, Object>>> internal(Exception exception) {
        LOGGER.error("operation=http_request status=failed error_code=INTERNAL_ERROR", exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "internal server error", null);
    }

    private static ResponseEntity<ApiEnvelope<Map<String, Object>>> response(
            HttpStatus status, String code, String message, Map<String, Object> data) {
        String requestId = RequestIds.current();
        ApiEnvelope<Map<String, Object>> envelope =
                new ApiEnvelope<>(code, message, requestId, data == null || data.isEmpty() ? null : data);
        return ResponseEntity.status(status).header(RequestIds.HEADER, requestId).body(envelope);
    }
}
