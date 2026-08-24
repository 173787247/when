package com.when.sink.http;

import com.when.core.InvalidConfigException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Resolves and checks HTTP targets before a request is sent. */
public final class HttpTargetPolicy {
    private final boolean allowLoopback;
    private final Set<String> allowedPrivateHosts;

    public HttpTargetPolicy(boolean allowLoopback, Set<String> allowedPrivateHosts) {
        this.allowLoopback = allowLoopback;
        Objects.requireNonNull(allowedPrivateHosts, "allowedPrivateHosts");
        Set<String> normalized = new HashSet<>();
        for (String host : allowedPrivateHosts) {
            if (host != null && !host.isBlank()) {
                normalized.add(host.toLowerCase(Locale.ROOT));
            }
        }
        this.allowedPrivateHosts = Set.copyOf(normalized);
    }

    public static HttpTargetPolicy production() {
        return new HttpTargetPolicy(false, Set.of());
    }

    public static HttpTargetPolicy developmentLoopback() {
        return new HttpTargetPolicy(true, Set.of());
    }

    public static HttpTargetPolicy fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    static HttpTargetPolicy fromEnvironment(Map<String, String> environment) {
        boolean loopback = Boolean.parseBoolean(
                environment.getOrDefault("WHEN_HTTP_SINK_ALLOW_LOOPBACK", "false"));
        String configured = environment.getOrDefault("WHEN_HTTP_SINK_PRIVATE_HOST_ALLOWLIST", "");
        Set<String> hosts = configured.isBlank()
                ? Set.of()
                : Set.copyOf(Arrays.stream(configured.split(","))
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .toList());
        return new HttpTargetPolicy(loopback, hosts);
    }

    public URI validate(String value) {
        final URI uri;
        try {
            uri = URI.create(Objects.requireNonNull(value, "url"));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new InvalidConfigException("HTTP sink URL is invalid", exception);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new InvalidConfigException("HTTP sink URL must be an absolute HTTP(S) URL");
        }
        if (uri.getUserInfo() != null) {
            throw new InvalidConfigException("HTTP sink URL must not contain user information");
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        boolean explicitlyAllowed = allowedPrivateHosts.contains(normalizedHost);
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                throw new InvalidConfigException("HTTP sink host did not resolve");
            }
            for (InetAddress address : addresses) {
                if (isLoopback(address)) {
                    if (!allowLoopback && !explicitlyAllowed) {
                        throw new InvalidConfigException("HTTP sink loopback target is not allowed");
                    }
                } else if (isRestricted(address) && !explicitlyAllowed) {
                    throw new InvalidConfigException("HTTP sink private or local target is not allowed");
                }
            }
        } catch (UnknownHostException exception) {
            throw new InvalidConfigException("HTTP sink host could not be resolved", exception);
        }
        return uri;
    }

    private static boolean isLoopback(InetAddress address) {
        return address.isLoopbackAddress();
    }

    private static boolean isRestricted(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return first == 0
                    || first == 100 && second >= 64 && second <= 127
                    || first >= 224;
        }
        if (address instanceof Inet6Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            return (first & 0xfe) == 0xfc;
        }
        return true;
    }
}
