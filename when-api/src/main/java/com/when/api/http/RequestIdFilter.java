package com.when.api.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** Establishes one bounded request ID before controllers, error mapping and access logs run. */
public final class RequestIdFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String requestId = RequestIds.normalize(request.getHeader(RequestIds.HEADER));
        request.setAttribute(RequestIds.ATTRIBUTE, requestId);
        response.setHeader(RequestIds.HEADER, requestId);
        long started = System.nanoTime();
        try (MDC.MDCCloseable ignored = MDC.putCloseable("request_id", requestId)) {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
            logger.info("operation=http_request status=" + response.getStatus()
                    + " method=" + request.getMethod()
                    + " path=" + request.getRequestURI()
                    + " duration_ms=" + durationMs);
        }
    }
}
