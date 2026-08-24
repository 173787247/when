package com.when.cluster.etcd;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Environment-backed connection settings. Secret values are never rendered by this type. */
public final class EtcdClientConfig {
    public static final String ENDPOINTS_ENV = "WHEN_ETCD_ENDPOINTS";
    public static final String USERNAME_ENV = "WHEN_ETCD_USERNAME";
    public static final String PASSWORD_ENV = "WHEN_ETCD_PASSWORD";
    public static final String CA_CERT_ENV = "WHEN_ETCD_CA_CERT";
    public static final String CLIENT_CERT_ENV = "WHEN_ETCD_CLIENT_CERT";
    public static final String CLIENT_KEY_ENV = "WHEN_ETCD_CLIENT_KEY";

    private final List<URI> endpoints;
    private final String username;
    private final String password;
    private final Path caCertificate;
    private final Path clientCertificate;
    private final Path clientKey;
    private final Duration operationTimeout;

    public EtcdClientConfig(List<URI> endpoints) {
        this(endpoints, null, null, null, null, null, Duration.ofSeconds(5));
    }

    public EtcdClientConfig(
            List<URI> endpoints,
            String username,
            String password,
            Path caCertificate,
            Path clientCertificate,
            Path clientKey,
            Duration operationTimeout) {
        Objects.requireNonNull(endpoints, "endpoints");
        if (endpoints.isEmpty() || endpoints.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("at least one endpoint is required");
        }
        endpoints.forEach(EtcdClientConfig::validateEndpoint);
        String normalizedUsername = blankToNull(username);
        String normalizedPassword = blankToNull(password);
        if ((normalizedUsername == null) != (normalizedPassword == null)) {
            throw new IllegalArgumentException("username and password must be configured together");
        }
        if ((clientCertificate == null) != (clientKey == null)) {
            throw new IllegalArgumentException("client certificate and key must be configured together");
        }
        this.operationTimeout = Objects.requireNonNull(operationTimeout, "operationTimeout");
        if (operationTimeout.isZero() || operationTimeout.isNegative()) {
            throw new IllegalArgumentException("operationTimeout must be positive");
        }
        this.endpoints = List.copyOf(endpoints);
        this.username = normalizedUsername;
        this.password = normalizedPassword;
        this.caCertificate = caCertificate;
        this.clientCertificate = clientCertificate;
        this.clientKey = clientKey;
    }

    public static EtcdClientConfig fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    public static EtcdClientConfig fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String rawEndpoints = environment.get(ENDPOINTS_ENV);
        if (rawEndpoints == null || rawEndpoints.isBlank()) {
            throw new IllegalStateException(ENDPOINTS_ENV + " is required");
        }
        List<URI> endpoints = new ArrayList<>();
        for (String endpoint : rawEndpoints.split(",")) {
            if (!endpoint.isBlank()) {
                endpoints.add(URI.create(endpoint.trim()));
            }
        }
        return new EtcdClientConfig(
                endpoints,
                blankToNull(environment.get(USERNAME_ENV)),
                blankToNull(environment.get(PASSWORD_ENV)),
                optionalPath(environment.get(CA_CERT_ENV)),
                optionalPath(environment.get(CLIENT_CERT_ENV)),
                optionalPath(environment.get(CLIENT_KEY_ENV)),
                Duration.ofSeconds(5));
    }

    public List<URI> endpoints() {
        return endpoints;
    }

    public Optional<String> username() {
        return Optional.ofNullable(username);
    }

    String password() {
        return password;
    }

    public Optional<Path> caCertificate() {
        return Optional.ofNullable(caCertificate);
    }

    public Optional<Path> clientCertificate() {
        return Optional.ofNullable(clientCertificate);
    }

    public Optional<Path> clientKey() {
        return Optional.ofNullable(clientKey);
    }

    public Duration operationTimeout() {
        return operationTimeout;
    }

    @Override
    public String toString() {
        return "EtcdClientConfig{endpoints=" + endpoints
                + ", usernameConfigured=" + (username != null)
                + ", tlsConfigured=" + (caCertificate != null || clientCertificate != null)
                + ", operationTimeout=" + operationTimeout + '}';
    }

    private static Path optionalPath(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : Path.of(normalized);
    }

    private static void validateEndpoint(URI endpoint) {
        String scheme = endpoint.getScheme();
        if (!endpoint.isAbsolute()
                || endpoint.getHost() == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("etcd endpoint must be an absolute HTTP(S) URI");
        }
        if (endpoint.getUserInfo() != null) {
            throw new IllegalArgumentException(
                    "etcd endpoint user-info is not allowed; use dedicated credential settings");
        }
        if (endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("etcd endpoint must not contain a query or fragment");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
