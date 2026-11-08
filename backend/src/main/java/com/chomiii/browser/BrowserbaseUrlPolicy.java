package com.chomiii.browser;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fail-closed egress policy. The integration is limited to exact HTTPS hosts
 * explicitly set by the operator; wildcard domains, IPs, and custom ports are
 * not supported.
 */
public final class BrowserbaseUrlPolicy {
    private final Set<String> hosts;

    public BrowserbaseUrlPolicy(String allowedHosts) {
        this.hosts = Arrays.stream(allowedHosts == null ? new String[0] : allowedHosts.split(","))
                .map(String::trim)
                .map(host -> host.toLowerCase(Locale.ROOT))
                .filter(host -> !host.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        if (hosts.stream().anyMatch(host -> !validHost(host))) {
            throw new IllegalArgumentException("Browserbase allowlist contains an invalid hostname");
        }
    }

    public URI requireAllowed(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 2048) {
            throw new IllegalArgumentException("Browser URL must be between 1 and 2048 characters");
        }
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid browser URL", invalid);
        }
        if (!isAllowed(uri)) {
            throw new IllegalArgumentException("Browser URL host is not allowed");
        }
        return uri;
    }

    public boolean isAllowed(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getRawUserInfo() != null || uri.getPort() != -1
                || uri.getHost() == null || uri.getRawFragment() != null) {
            return false;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return validHost(host) && hosts.contains(host);
    }

    private static boolean validHost(String host) {
        return host.length() <= 253 && host.contains(".")
                && host.matches("[a-z0-9-]+(\\.[a-z0-9-]+)+")
                && !host.endsWith(".local") && !host.endsWith(".internal")
                && !host.endsWith(".localhost")
                && !host.matches("[0-9.]+")
                && !host.startsWith("-") && !host.contains(".-")
                && !host.contains("-.") && !host.endsWith("-");
    }
}
