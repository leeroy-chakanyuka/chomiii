package com.chomiii.evaluation;

import com.chomiii.ChomiiiApplication;
import com.chomiii.chat.ChatTurnStore;
import com.chomiii.chat.ConversationRepository;
import com.chomiii.memory.MemoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Starts the application only after checking that the database is explicitly disposable. */
final class DisposableEvaluationDatabase implements AutoCloseable {
    private final ConfigurableApplicationContext context;
    private final JdbcTemplate jdbc;
    private final ConversationRepository conversations;
    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl;
    private final String origin;

    private DisposableEvaluationDatabase(ConfigurableApplicationContext context) {
        this.context = context;
        this.jdbc = context.getBean(JdbcTemplate.class);
        this.conversations = context.getBean(ConversationRepository.class);
        this.baseUrl = "http://127.0.0.1:" + ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        this.origin = context.getEnvironment().getProperty("chomiii.public-origin", "http://localhost:5173");
    }

    static void validate(Map<String, String> environment) {
        if (!"true".equals(environment.get("EVAL_DISPOSABLE_DB"))) {
            throw new IllegalArgumentException("Set EVAL_DISPOSABLE_DB=true for an isolated evaluation database");
        }
        String url = environment.get("CHOMIII_DB_URL");
        if (url == null || !url.startsWith("jdbc:")) throw new IllegalArgumentException("A disposable JDBC URL is required");
        URI uri;
        try { uri = URI.create(url.substring(5)); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid disposable JDBC URL"); }
        if (!("postgresql".equals(uri.getScheme()) || "mysql".equals(uri.getScheme()))
                || !("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()))
                || uri.getUserInfo() != null || uri.getFragment() != null || uri.getPath() == null
                || !uri.getPath().matches("/chomiii_eval_[a-z0-9_]{1,48}")) {
            throw new IllegalArgumentException("Evaluation requires a loopback database named chomiii_eval_*");
        }
        if (uri.getRawQuery() != null) {
            for (String parameter : uri.getRawQuery().split("&")) {
                if (!parameter.matches("(?i)(useSSL|allowPublicKeyRetrieval)=(true|false)")
                        && !parameter.matches("(?i)sslmode=(disable|prefer|require)")
                        && !parameter.matches("connectTimeout=[0-9]+")) {
                    throw new IllegalArgumentException("Unsupported evaluation database URL parameter");
                }
            }
        }
        if (environment.get("CHOMIII_DB_USER") == null || environment.get("CHOMIII_DB_USER").isBlank()
                || !environment.containsKey("CHOMIII_DB_PASSWORD")) {
            throw new IllegalArgumentException("Disposable database credentials are required");
        }
    }

    static DisposableEvaluationDatabase start() {
        Map<String, String> environment = System.getenv();
        validate(environment);
        var pinned = new java.util.HashMap<String, Object>();
        pinned.put("spring.datasource.url", environment.get("CHOMIII_DB_URL"));
        pinned.put("spring.datasource.username", environment.get("CHOMIII_DB_USER"));
        pinned.put("spring.datasource.password", environment.get("CHOMIII_DB_PASSWORD"));
        pinned.put("spring.flyway.url", environment.get("CHOMIII_DB_URL"));
        pinned.put("spring.flyway.user", environment.get("CHOMIII_DB_USER"));
        pinned.put("spring.flyway.password", environment.get("CHOMIII_DB_PASSWORD"));
        pinned.put("spring.flyway.locations", "classpath:db/migration");
        pinned.put("spring.flyway.clean-disabled", "true");
        var context = new SpringApplicationBuilder(ChomiiiApplication.class)
                .initializers(application -> application.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("evaluation-disposable-database", pinned)))
                .web(WebApplicationType.SERVLET)
                .run("--spring.profiles.active=local", "--server.address=127.0.0.1", "--server.port=0",
                        "--chomiii.session.secure=false", "--chomiii.public-origin=http://localhost:5173");
        return new DisposableEvaluationDatabase(context);
    }

    Session session() throws Exception {
        String cookie = newCookie();
        String otherCookie = newCookie();
        String owner = owner(cookie);
        String other = owner(otherCookie);
        Instant now = Instant.now();
        jdbc.update("insert into browser_sessions (owner_hash, created_at) values (?, ?)", owner, Timestamp.from(now));
        jdbc.update("insert into browser_sessions (owner_hash, created_at) values (?, ?)", other, Timestamp.from(now));
        UUID conversation = UUID.randomUUID();
        conversations.create(conversation, owner, now);
        return new Session(owner, other, conversation, cookie, otherCookie);
    }

    MemoryService memories() { return context.getBean(MemoryService.class); }
    com.chomiii.memory.embedding.EmbeddingStore embeddings() {
        return context.getBean(com.chomiii.memory.embedding.EmbeddingStore.class);
    }
    ChatTurnStore turns() { return context.getBean(ChatTurnStore.class); }
    ConversationRepository conversations() { return conversations; }

    private static String newCookie() {
        byte[] token = new byte[32];
        new SecureRandom().nextBytes(token);
        return "chomiii_session=" + Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    private static String owner(String cookie) throws Exception {
        String token = cookie.substring(cookie.indexOf('=') + 1);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.US_ASCII)));
    }

    boolean apiIsolation(Session session, UUID memoryId) throws Exception {
        if (!apiContains(session.cookie, memoryId)) return false;
        var list = HttpRequest.newBuilder(URI.create(baseUrl + "/api/memories"))
                .header("Cookie", session.otherCookie).GET().build();
        var response = http.send(list, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return false;
        var rows = new ObjectMapper().readTree(response.body());
        if (!rows.isArray()) return false;
        for (var row : rows) if (memoryId.toString().equals(row.path("id").asText())) return false;
        var delete = HttpRequest.newBuilder(URI.create(baseUrl + "/api/memories/" + memoryId))
                .header("Cookie", session.otherCookie).header("Origin", origin).DELETE().build();
        return http.send(delete, HttpResponse.BodyHandlers.ofString()).statusCode() == 404
                && apiContains(session.cookie, memoryId);
    }

    private boolean apiContains(String cookie, UUID memoryId) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/memories"))
                .header("Cookie", cookie).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return false;
        var rows = new ObjectMapper().readTree(response.body());
        if (!rows.isArray()) return false;
        for (var row : rows) if (memoryId.toString().equals(row.path("id").asText())) return true;
        return false;
    }

    @Override public void close() { context.close(); }

    final class Session implements AutoCloseable {
        private final String owner;
        private final String other;
        private final UUID conversation;
        private final String cookie;
        private final String otherCookie;

        private Session(String owner, String other, UUID conversation, String cookie, String otherCookie) {
            this.owner = owner;
            this.other = other;
            this.conversation = conversation;
            this.cookie = cookie;
            this.otherCookie = otherCookie;
        }

        String owner() { return owner; }
        String other() { return other; }
        UUID conversation() { return conversation; }

        @Override public void close() {
            jdbc.update("delete from browser_sessions where owner_hash = ?", owner);
            jdbc.update("delete from browser_sessions where owner_hash = ?", other);
        }
    }
}
