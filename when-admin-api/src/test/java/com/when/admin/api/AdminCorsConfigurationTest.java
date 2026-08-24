package com.when.admin.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AdminCorsConfigurationTest {
    @Test
    void requiresExplicitTrustedOriginsAndRejectsWildcard() {
        assertDoesNotThrow(() -> new AdminCorsConfiguration("https://ops.example,https://backup.example"));
        assertThrows(IllegalArgumentException.class, () -> new AdminCorsConfiguration("*"));
        assertThrows(IllegalArgumentException.class, () -> new AdminCorsConfiguration("  "));
    }
}
