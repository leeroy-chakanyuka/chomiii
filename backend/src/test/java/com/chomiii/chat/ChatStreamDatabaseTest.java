package com.chomiii.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.chomiii.provider.ChatProvider;
import com.chomiii.provider.ProviderFailure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@EnabledIfEnvironmentVariable(named = "CHOMIII_DB_URL", matches = "jdbc:(postgresql|mysql):.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class ChatStreamDatabaseTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ChatProvider provider;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void streamsAndStoresTwoTurnsWithEarlierRolesAndNoUnrelatedHistory() throws Exception {
        when(provider.id()).thenReturn("gemini");
        List<ChatProvider.ChatInput> sent = new ArrayList<>();
        when(provider.open(any(), anyString())).thenAnswer(invocation -> {
            ChatProvider.ChatInput input = invocation.getArgument(0);
            sent.add(input);
            return new ChatProvider.Generation() {
                @Override public void stream(Consumer<String> delta) {
                    delta.accept("From ");
                    delta.accept(input.messages().getLast().text().contains("Which") ? "Java" : "Chomi");
                }
                @Override public void close() {}
            };
        });
        String cookie = session();
        String first = conversation(cookie);
        String separate = conversation(cookie);
        assertThat(send(cookie, separate, UUID.randomUUID(), "Unrelated question").body()).contains("event:complete");

        var firstResponse = send(cookie, first, UUID.randomUUID(), "My favourite language is Java");
        assertThat(firstResponse.statusCode()).isEqualTo(200);
        assertThat(firstResponse.headers().firstValue("content-type").orElse("")).contains("text/event-stream");
        assertThat(firstResponse.body()).contains("event:start", "event:delta", "From ", "Chomi", "event:complete");
        var secondResponse = send(cookie, first, UUID.randomUUID(), "Which language did I mention?");
        assertThat(secondResponse.body()).contains("event:complete", "Java");
        JsonNode messages = json.readTree(get(cookie, "/api/conversations/" + first).body()).path("messages");
        assertThat(messages).hasSize(4);
        assertThat(messages.get(1).path("content").asText()).isEqualTo("From Chomi");
        assertThat(messages.get(3).path("status").asText()).isEqualTo("complete");
        assertThat(sent.getLast().messages()).containsExactly(
                new ChatProvider.Message(ChatProvider.Role.USER, "My favourite language is Java"),
                new ChatProvider.Message(ChatProvider.Role.ASSISTANT, "From Chomi"),
                new ChatProvider.Message(ChatProvider.Role.USER, "Which language did I mention?"));
    }

    @Test
    void rejectsMissingKeysUnsupportedModelsForeignSessionsAndDuplicateSubmissions() throws Exception {
        when(provider.id()).thenReturn("gemini");
        when(provider.open(any(), anyString())).thenReturn(new ChatProvider.Generation() {
            @Override public void stream(Consumer<String> delta) { delta.accept("success"); }
            @Override public void close() {}
        });
        String owner = session();
        String other = session();
        String conversation = conversation(owner);
        UUID id = UUID.randomUUID();
        assertThat(send(other, conversation, id, "No access").statusCode()).isEqualTo(404);
        assertThat(sendWith(owner, conversation, id, "Hello", " ", "gemini-3.5-flash-lite").statusCode())
                .isEqualTo(400);
        assertThat(sendWith(owner, conversation, id, "Hello", "fake-secret", "unlisted-model").statusCode())
                .isEqualTo(400);
        assertThat(send(owner, conversation, id, "Hello").body()).contains("event:complete");
        assertThat(send(owner, conversation, id, "Hello").statusCode()).isEqualTo(409);
        assertThat(json.readTree(get(owner, "/api/conversations/" + conversation).body()).path("messages"))
                .hasSize(2);
        assertThat(jdbc.queryForObject("select count(*) from messages where content like ?", Integer.class,
                "%fake-secret%")).isZero();
    }

    @Test
    void persistsPartialFailureAsFailedAndDoesNotSendFailedAssistantToNextTurn() throws Exception {
        when(provider.id()).thenReturn("gemini");
        List<ChatProvider.ChatInput> sent = new ArrayList<>();
        when(provider.open(any(), anyString())).thenAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            boolean first = sent.size() == 1;
            return new ChatProvider.Generation() {
                @Override public void stream(Consumer<String> delta) {
                    if (first) {
                        delta.accept("unfinished");
                        throw new ProviderFailure(ProviderFailure.Code.RATE_LIMITED, "Rate limited");
                    }
                    delta.accept("recovered");
                }
                @Override public void close() {}
            };
        });
        String cookie = session();
        String conversation = conversation(cookie);
        var error = send(cookie, conversation, UUID.randomUUID(), "First question");
        assertThat(error.body()).contains("event:delta", "event:error", "RATE_LIMITED");
        JsonNode history = json.readTree(get(cookie, "/api/conversations/" + conversation).body()).path("messages");
        assertThat(history.get(1).path("status").asText()).isEqualTo("failed");
        assertThat(history.get(1).path("content").asText()).isEqualTo("unfinished");
        assertThat(send(cookie, conversation, UUID.randomUUID(), "Second question").body())
                .contains("event:complete");
        assertThat(sent.getLast().messages()).containsExactly(
                new ChatProvider.Message(ChatProvider.Role.USER, "First question"),
                new ChatProvider.Message(ChatProvider.Role.USER, "Second question"));
    }

    @Test
    void deliversFirstChunkBeforeGenerationFinishesAndCanStopIt() throws Exception {
        when(provider.id()).thenReturn("gemini");
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        when(provider.open(any(), anyString())).thenAnswer(invocation -> new ChatProvider.Generation() {
            @Override public void stream(Consumer<String> delta) {
                delta.accept("early");
                try {
                    release.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                if (stopped.getCount() == 0) {
                    throw new ProviderFailure(ProviderFailure.Code.CANCELLED, "stopped");
                }
                delta.accept(" late");
            }
            @Override public void close() { stopped.countDown(); release.countDown(); }
        });
        String cookie = session();
        String conversation = conversation(cookie);
        UUID requestId = UUID.randomUUID();
        String path = "/api/conversations/" + conversation + "/messages/stream";
        String body = json.writeValueAsString(new MessageRequest(requestId, "gemini", "gemini-3.5-flash-lite", "Hello"));
        HttpRequest request = request(path, cookie).header("Content-Type", "application/json")
                .header("X-Gemini-Api-Key", "fake-secret")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(response.body()))) {
            String line;
            do {
                line = reader.readLine();
            } while (line != null && !line.contains("early"));
            assertThat(line).contains("early");
            assertThat(release.getCount()).isEqualTo(1);
            assertThat(send(cookie, conversation, requestId, "Hello").statusCode()).isEqualTo(409);
            var cancelled = client.send(request("/api/conversations/" + conversation + "/messages/"
                    + requestId + "/cancel", cookie).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(cancelled.statusCode()).isEqualTo(202);
            assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(reader.lines().toList().toString()).contains("event:error", "INTERRUPTED");
        } finally {
            release.countDown();
        }
        JsonNode messages = json.readTree(get(cookie, "/api/conversations/" + conversation).body()).path("messages");
        assertThat(messages.get(1).path("status").asText()).isEqualTo("interrupted");
    }

    private String session() throws Exception {
        return get(null, "/api/conversations").headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
    }

    private String conversation(String cookie) throws Exception {
        HttpResponse<String> response = client.send(request("/api/conversations", cookie)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        return json.readTree(response.body()).path("id").asText();
    }

    private HttpResponse<String> send(String cookie, String conversation, UUID requestId, String content) throws Exception {
        return sendWith(cookie, conversation, requestId, content, "fake-secret", "gemini-3.5-flash-lite");
    }

    private HttpResponse<String> sendWith(String cookie, String conversation, UUID requestId, String content,
            String key, String model) throws Exception {
        String body = json.writeValueAsString(new MessageRequest(requestId, "gemini", model, content));
        return client.send(request("/api/conversations/" + conversation + "/messages/stream", cookie)
                .header("X-Gemini-Api-Key", key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String cookie, String path) throws Exception {
        return client.send(request(path, cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path, String cookie) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30)).header("Origin", "http://localhost:5173");
        return cookie == null ? builder : builder.header("Cookie", cookie);
    }

    private record MessageRequest(UUID requestId, String provider, String model, String content) {}
}
