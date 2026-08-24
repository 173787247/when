package com.when.admin.api;

import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Explicit trusted-origin CORS policy. A wildcard is rejected instead of silently accepted. */
@Configuration
public final class AdminCorsConfiguration implements WebMvcConfigurer {
    private final List<String> allowedOrigins;

    public AdminCorsConfiguration() {
        this(System.getenv().getOrDefault("WHEN_ADMIN_ALLOWED_ORIGINS", "http://localhost:5173"));
    }

    AdminCorsConfiguration(String configuredOrigins) {
        this.allowedOrigins = Arrays.stream(configuredOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
        if (allowedOrigins.isEmpty() || allowedOrigins.contains("*")) {
            throw new IllegalArgumentException("admin CORS requires an explicit origin allowlist");
        }
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/admin/v1/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "DELETE")
                .allowedHeaders("Content-Type", "X-Request-Id", "Idempotency-Key")
                .exposedHeaders("X-Request-Id", "Idempotency-Replayed", "Location")
                .allowCredentials(false)
                .maxAge(600);
    }
}
