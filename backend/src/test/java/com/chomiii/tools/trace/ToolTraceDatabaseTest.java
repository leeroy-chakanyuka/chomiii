package com.chomiii.tools.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chomiii.chat.ConversationNotFoundException;
import com.chomiii.chat.ConversationRepository;
import com.chomiii.chat.ChatStreamController;
import com.chomiii.chat.ChatStreamService;
import com.chomiii.chat.ChatTurnStore;
import com.chomiii.memory.MemoryLearningService;
import com.chomiii.memory.MemoryService;
import com.chomiii.provider.ChatProvider;
import com.chomiii.provider.ProviderCatalog;
import com.chomiii.tools.InternalTool;
import com.chomiii.tools.ToolDefinition;
import com.chomiii.tools.ToolExecutionContext;
import com.chomiii.tools.ToolRegistry;
import com.chomiii.tools.ToolTurn;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@EnabledIfEnvironmentVariable(named = "CHOMIII_DB_URL", matches = "jdbc:(postgresql|mysql):.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class ToolTraceDatabaseTest {
    @Autowired ToolTraceService traces;
    @Autowired ToolRegistry registry;
    @Autowired MemoryService memories;
    @Autowired ConversationRepository conversations;
    @Autowired ChatTurnStore store;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    private String owner;
    private String other;
    private String ownerToken;
    private String otherToken;
    private UUID conversation;
    private UUID request;

    @BeforeEach
    void createSession() {
        ownerToken = token();
        otherToken = token();
        owner = ownerId(ownerToken);
        other = ownerId(otherToken);
        var now = Instant.now();
        jdbc.update("insert into browser_sessions (owner_hash, created_at) values (?, ?)", owner, Timestamp.from(now));
        jdbc.update("insert into browser_sessions (owner_hash, created_at) values (?, ?)", other, Timestamp.from(now));
        conversation = UUID.randomUUID();
        request = UUID.randomUUID();
        conversations.create(conversation, owner, now);
    }

    @AfterEach
    void removeSession() {
        jdbc.update("delete from browser_sessions where owner_hash in (?, ?)", owner, other);
    }

    @Test
    void successfulSearchIsRecordedWithoutPrivateArgumentsResultsOrKeys() throws Exception {
        memories.create(owner, "My private dinner preference is tacos");
        var events = new ArrayList<ToolTraceDto.Trace>();
        var turn = turn(registry, events);

        assertThat(turn.invoke("memory.search", Map.of("query", "private dinner"))).containsKey("output");

        var history = traces.list(conversation, owner);
        assertThat(history).hasSize(1);
        var trace = history.getFirst();
        assertThat(trace.toolName()).isEqualTo("memory.search");
        assertThat(trace.capability()).isEqualTo("memory.read");
        assertThat(trace.authorization()).isEqualTo("ALLOW");
        assertThat(trace.status()).isEqualTo("succeeded");
        assertThat(trace.startedAt()).isNotNull();
        assertThat(trace.finishedAt()).isNotNull();
        assertThat(trace.durationMs()).isNotNegative();
        assertThat(events).extracting(ToolTraceDto.Trace::status).containsExactly("requested", "running", "succeeded");
        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(trace))
                .doesNotContain("private dinner", "tacos", "fake-api-secret");
        assertThatThrownBy(() -> traces.list(conversation, other)).isInstanceOf(ConversationNotFoundException.class);
    }

    @Test
    void successRequiresAuthorizationAndStart() {
        var requested = traces.recordRequested(owner, new ToolTraceCommand.Requested(conversation, request,
                null, null, "memory.search", "memory.read", ToolDefinition.Effect.READ,
                "gemini", "test-model", null, null, Map.of()));

        assertThatThrownBy(() -> traces.recordFinished(owner, requested.invocationId(), "succeeded", null, Map.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(traces.list(conversation, owner).getFirst().status()).isEqualTo("requested");

        traces.recordAuthorized(owner, requested.invocationId(), null);
        traces.recordStarted(owner, requested.invocationId());
        var finished = traces.recordFinished(owner, requested.invocationId(), "succeeded", null, Map.of());

        assertThat(finished.authorization()).isEqualTo("ALLOW");
        assertThat(finished.status()).isEqualTo("succeeded");
        assertThat(finished.startedAt()).isNotNull();
        assertThat(finished.finishedAt()).isNotNull();
    }

    @Test
    void denialsAndUnknownToolAttemptsAreRecordedWithoutExecutingOrSavingNames() {
        var called = new java.util.concurrent.atomic.AtomicBoolean();
        InternalTool privateRead = tool("mail.read", "mail.read", () -> called.set(true));
        var turn = turn(new ToolRegistry(List.of(privateRead)), new ArrayList<>());

        assertThat(turn.invoke("mail.read", Map.of())).containsEntry("errorCode", "NOT_AUTHORIZED");
        assertThat(turn.invoke("a-private-secret", Map.of("SensitiveKey123", "private-value")))
                .containsEntry("errorCode", "NOT_AVAILABLE");

        assertThat(called).isFalse();
        assertThat(traces.list(conversation, owner)).extracting(ToolTraceDto.Trace::status)
                .containsExactly("denied", "failed");
        assertThat(traces.list(conversation, owner).getLast().toolName()).isEqualTo("unknown");
        assertThat(traces.list(conversation, owner).toString())
                .doesNotContain("a-private-secret", "SensitiveKey123", "private-value");
    }

    @Test
    void failedAndTimedOutExecutionHaveTruthfulTerminalStates() {
        InternalTool broken = tool("broken", "memory.read", () -> { throw new IllegalStateException("private-error"); });
        InternalTool slow = tool("slow", "memory.read", () -> {
            try { Thread.sleep(30_000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        var turn = turn(new ToolRegistry(List.of(broken, slow)), new ArrayList<>());

        assertThat(turn.invoke("broken", Map.of())).containsEntry("errorCode", "FAILURE");
        assertThat(turn.invoke("slow", Map.of())).containsEntry("errorCode", "TIMEOUT");

        assertThat(traces.list(conversation, owner)).extracting(ToolTraceDto.Trace::status)
                .containsExactly("failed", "timeout");
        assertThat(traces.list(conversation, owner).toString()).doesNotContain("private-error");
    }

    @Test
    void cancellationInterruptsExecutionAndRecordsTheCancellation() throws Exception {
        var entered = new CountDownLatch(1);
        InternalTool slow = tool("slow", "memory.read", () -> {
            entered.countDown();
            try { Thread.sleep(30_000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        var turn = turn(new ToolRegistry(List.of(slow)), new ArrayList<>());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var response = executor.submit(() -> turn.invoke("slow", Map.of()));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            turn.close();
            assertThat(response.get(5, TimeUnit.SECONDS)).containsEntry("errorCode", "CANCELLED");
        }
        assertThat(traces.list(conversation, owner)).extracting(ToolTraceDto.Trace::status).containsExactly("cancelled");
    }

    @Test
    void traceEndpointReturnsOnlyTheOwnersConversation() throws Exception {
        memories.create(owner, "I like tacos for dinner");
        turn(registry, new ArrayList<>()).invoke("memory.search", Map.of("query", "dinner"));
        var uri = java.net.URI.create("http://127.0.0.1:" + port + "/api/conversations/" + conversation + "/tool-traces");
        var client = java.net.http.HttpClient.newHttpClient();
        var mine = java.net.http.HttpRequest.newBuilder(uri).header("Cookie", "chomiii_session=" + ownerToken).GET().build();
        var foreign = java.net.http.HttpRequest.newBuilder(uri).header("Cookie", "chomiii_session=" + otherToken).GET().build();
        var response = client.send(mine, java.net.http.HttpResponse.BodyHandlers.ofString());
        var denied = client.send(foreign, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("memory.search", "succeeded").doesNotContain("tacos", "dinner");
        assertThat(denied.statusCode()).isEqualTo(404);
        assertThat(denied.body()).doesNotContain("memory.search", "tacos");
    }

    @Test
    void normalChatUsesTheSamePersistedToolRuntime() throws Exception {
        memories.create(owner, "I like tacos for dinner");
        var invoked = new CountDownLatch(1);
        var result = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
        ChatProvider fake = new ChatProvider() {
            @Override public String id() { return "gemini"; }
            @Override public Generation open(ChatInput input, String key) { throw new AssertionError("Tool was not supplied"); }
            @Override public Generation open(ChatInput input, String key, Tools tools) {
                return new Generation() {
                    @Override public void stream(java.util.function.Consumer<String> delta) {
                        try { result.set(tools.invoke("memory.search", Map.of("query", "dinner"))); }
                        finally { invoked.countDown(); }
                        delta.accept("Your saved preference is available.");
                    }
                    @Override public void close() { }
                };
            }
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var chat = new ChatStreamService(store, conversations, memories,
                    org.mockito.Mockito.mock(MemoryLearningService.class),
                    new ProviderCatalog(List.of("test-model")), List.of(fake), executor,
                    true, null, null, registry, traces);
            chat.start(conversation, owner, new ChatStreamController.MessageRequest(
                    request, "gemini", "test-model", "What should I eat?"), "fake-api-secret");
            assertThat(invoked.await(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(result.get()).containsKey("output");
        assertThat(traces.list(conversation, owner)).singleElement().satisfies(trace -> {
            assertThat(trace.requestId()).isEqualTo(request);
            assertThat(trace.status()).isEqualTo("succeeded");
            assertThat(trace.provider()).isEqualTo("gemini");
            assertThat(trace.model()).isEqualTo("test-model");
        });
    }

    private ToolTurn turn(ToolRegistry tools, List<ToolTraceDto.Trace> events) {
        var context = new ToolExecutionContext(owner, conversation, request, "fake-api-secret", true, ignored -> {});
        return new ToolTurn(tools, context, false, traces, "gemini", "test-model", events::add);
    }

    private static InternalTool tool(String name, String capability, Runnable action) {
        return new InternalTool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition(name, "Test", Map.of("type", "object"), capability, ToolDefinition.Effect.READ);
            }
            @Override public Result execute(ToolExecutionContext context, Map<String, Object> arguments) {
                action.run();
                return new Result(Map.of(), () -> {});
            }
        };
    }

    private static String token() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String ownerId(String token) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
