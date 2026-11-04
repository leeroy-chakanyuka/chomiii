package com.chomiii.session;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

public final class AnonymousSessionFilter extends OncePerRequestFilter {
    public static final String OWNER_ATTRIBUTE = "chomiii.session.owner";
    private final JdbcTemplate jdbc;
    private final boolean secure;
    private final String origin;
    private final String cookieName;
    private final SecureRandom random = new SecureRandom();
    private final RequestRateLimiter rateLimiter = new RequestRateLimiter(60, 4096);

    public AnonymousSessionFilter(JdbcTemplate jdbc, boolean secure, String origin) {
        this.jdbc = jdbc;
        this.secure = secure;
        URI uri = URI.create(origin);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || (uri.getPath() != null && !uri.getPath().isEmpty())
                || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || (secure && !"https".equals(uri.getScheme()))) {
            throw new IllegalArgumentException("A valid public origin is required");
        }
        this.origin = origin;
        this.cookieName = secure ? "__Host-chomiii_session" : "chomiii_session";
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        if (secure && !request.isSecure()) {
            reject(response, 400, "HTTPS is required");
            return;
        }
        boolean safe = "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
        if (!safe && !sameOrigin(request.getHeader(HttpHeaders.ORIGIN))) {
            reject(response, 403, "Origin is not allowed");
            return;
        }
        String owner = knownOwner(request);
        String limitKey = owner == null ? "ip:" + request.getRemoteAddr() : "owner:" + owner;
        if (!rateLimiter.allow(limitKey, Instant.now())) {
            response.setHeader(HttpHeaders.RETRY_AFTER, "60");
            reject(response, 429, "Too many requests");
            return;
        }
        if (owner == null) {
            if (!safe) {
                reject(response, 401, "A browser session is required");
                return;
            }
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            owner = hash(token);
            jdbc.update("insert into browser_sessions (owner_hash, created_at) values (?, ?)",
                    owner, Timestamp.from(Instant.now()));
            ResponseCookie cookie = ResponseCookie.from(cookieName, token)
                    .httpOnly(true).secure(secure).sameSite("Lax").path("/")
                    .maxAge(Duration.ofDays(180)).build();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        request.setAttribute(OWNER_ATTRIBUTE, owner);
        chain.doFilter(request, response);
    }

    private String knownOwner(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (cookieName.equals(cookie.getName()) && cookie.getValue().matches("[A-Za-z0-9_-]{43}")) {
                String owner = hash(cookie.getValue());
                Integer count = jdbc.queryForObject("select count(*) from browser_sessions where owner_hash = ?",
                        Integer.class, owner);
                return count != null && count == 1 ? owner : null;
            }
        }
        return null;
    }

    private boolean sameOrigin(String candidate) {
        if (origin.equals(candidate)) {
            return true;
        }
        if (secure || candidate == null) {
            return false;
        }
        URI configured = URI.create(origin);
        URI incoming;
        try {
            incoming = URI.create(candidate);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        return "http".equals(configured.getScheme()) && "http".equals(incoming.getScheme())
                && configured.getPort() == incoming.getPort()
                && isLoopback(configured.getHost()) && isLoopback(incoming.getHost());
    }

    private static boolean isLoopback(String host) {
        return "localhost".equals(host) || "127.0.0.1".equals(host);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void reject(HttpServletResponse response, int status, String title) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"status\":" + status + ",\"title\":\"" + title + "\"}");
    }
}
