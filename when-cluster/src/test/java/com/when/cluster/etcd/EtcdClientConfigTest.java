package com.when.cluster.etcd;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EtcdClientConfigTest {
    @Test
    void readsEndpointsAndCredentialsOnlyFromEnvironment() {
        Map<String, String> environment = new HashMap<>();
        environment.put(EtcdClientConfig.ENDPOINTS_ENV, "http://127.0.0.1:2379,http://127.0.0.1:2381");
        environment.put(EtcdClientConfig.USERNAME_ENV, "when");
        environment.put(EtcdClientConfig.PASSWORD_ENV, "secret-value");

        EtcdClientConfig config = EtcdClientConfig.fromEnvironment(environment);

        assertEquals(2, config.endpoints().size());
        assertEquals("when", config.username().orElseThrow());
        assertFalse(config.toString().contains("secret-value"));
    }

    @Test
    void requiresEndpoints() {
        assertThrows(IllegalStateException.class,
                () -> EtcdClientConfig.fromEnvironment(Map.of()));
    }

    @Test
    void rejectsPartialCredentialsIncludingBlankValues() {
        Map<String, String> environment = new HashMap<>();
        environment.put(EtcdClientConfig.ENDPOINTS_ENV, "http://127.0.0.1:2379");
        environment.put(EtcdClientConfig.USERNAME_ENV, "when");
        environment.put(EtcdClientConfig.PASSWORD_ENV, " ");

        assertThrows(IllegalArgumentException.class,
                () -> EtcdClientConfig.fromEnvironment(environment));
    }

    @Test
    void rejectsCredentialsEmbeddedInEndpoint() {
        assertThrows(IllegalArgumentException.class,
                () -> new EtcdClientConfig(List.of(
                        URI.create("http://user:secret-value@127.0.0.1:2379"))));
    }
}
