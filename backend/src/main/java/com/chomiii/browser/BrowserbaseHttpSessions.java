package com.chomiii.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Minimal REST client; no unmaintained Stagehand Java SDK is required. */
@Component
public class BrowserbaseHttpSessions implements BrowserbaseSessions {
    private static final URI API = URI.create("https://api.browserbase.com/v1/sessions");
    private final HttpClient http;
    private final ObjectMapper json;
    private final String apiKey;
    private final String projectId;

    public BrowserbaseHttpSessions(ObjectMapper json,
            @Value("${chomiii.browserbase.api-key:}") String apiKey,
            @Value("${chomiii.browserbase.project-id:}") String projectId) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                json, apiKey, projectId);
    }

    BrowserbaseHttpSessions(HttpClient http, ObjectMapper json, String apiKey, String projectId) {
        this.http = http;
        this.json = json;
        this.apiKey = apiKey;
        this.projectId = projectId;
    }

    @Override
    public Session create() {
        requireConfiguration();
        try {
            String body = json.createObjectNode().put("projectId", projectId).toString();
            HttpRequest request = request(API, body);
            JsonNode response = json.readTree(send(request));
            String id = response.path("id").asText("");
            String connectUrl = response.path("connectUrl").asText("");
            URI endpoint = URI.create(connectUrl);
            if (id.isBlank() || !id.matches("[a-zA-Z0-9_-]{8,100}")
                    || !"wss".equalsIgnoreCase(endpoint.getScheme())
                    || endpoint.getHost() == null
                    || !(endpoint.getHost().equalsIgnoreCase("browserbase.com")
                        || endpoint.getHost().toLowerCase(java.util.Locale.ROOT).endsWith(".browserbase.com"))) {
                throw new IllegalStateException("Browserbase returned an invalid session");
            }
            return new Session(id, connectUrl);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Browserbase session creation failed", e);
        }
    }

    @Override
    public void release(String id) {
        requireConfiguration();
        if (id == null || !id.matches("[a-zA-Z0-9_-]{8,100}")) {
            throw new IllegalArgumentException("Invalid Browserbase session ID");
        }
        try {
            String body = json.createObjectNode().put("status", "REQUEST_RELEASE").toString();
            send(request(URI.create(API + "/" + id), body));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Browserbase session release failed", e);
        }
    }

    private HttpRequest request(URI endpoint, String jsonBody) {
        return HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(20))
                .header("X-BB-API-Key", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();
    }

    private String send(HttpRequest request) throws java.io.IOException {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Browserbase request interrupted", interrupted);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // Deliberately do not print provider bodies: they may include secrets.
            throw new IllegalStateException("Browserbase request failed (HTTP " + response.statusCode() + ")");
        }
        return response.body();
    }

    private void requireConfiguration() {
        if (apiKey == null || apiKey.isBlank() || projectId == null || projectId.isBlank()) {
            throw new IllegalStateException("Browserbase credentials are not configured");
        }
    }
}
