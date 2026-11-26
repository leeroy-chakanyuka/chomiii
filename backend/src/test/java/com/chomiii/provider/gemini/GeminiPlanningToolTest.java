package com.chomiii.provider.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import com.chomiii.planning.PlanningTurn;
import com.chomiii.provider.ChatProvider;
import com.chomiii.tools.InternalTool;
import com.chomiii.tools.ToolCallBudget;
import com.chomiii.tools.ToolDefinition;
import com.chomiii.tools.ToolExecutionContext;
import com.chomiii.tools.ToolRegistry;
import com.chomiii.tools.ToolTurn;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GeminiPlanningToolTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void providerUsesTheInternalToolOnlyWhenTheModelSelectsIt(boolean selectPlan) throws Exception {
        var requests = new CopyOnWriteArrayList<String>();
        var json = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var parts = selectPlan && requests.size() == 1
                    ? List.of(Map.of("text", "Private plan draft"), Map.of("functionCall",
                            Map.of("name", "plan.stage", "args", Map.of("steps", List.of(Map.of("create", Map.of("title", "Milk")))))))
                    : List.of(Map.of("text", "Let's work through your tasks."));
            String response = "data: " + json.writeValueAsString(Map.of("candidates", List.of(
                    Map.of("content", Map.of("role", "model", "parts", parts))))) + "\n\n";
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) { out.write(response.getBytes(StandardCharsets.UTF_8)); }
        });
        server.start();
        try {
            InternalTool create = new InternalTool() {
                @Override public ToolDefinition definition() { return new ToolDefinition("todo.create", "Create task",
                        Map.of("type", "object"), "todo.write", ToolDefinition.Effect.WRITE); }
                @Override public Result execute(ToolExecutionContext context, Map<String, Object> arguments) {
                    throw new AssertionError("A planning call cannot write");
                }
            };
            var budget = new ToolCallBudget();
            var turn = new ToolTurn(new ToolRegistry(List.of(create)), new ToolExecutionContext("owner",
                    UUID.randomUUID(), UUID.randomUUID(), null, false, ignored -> {}), true,
                    null, "gemini", "model", ignored -> {}, null, ignored -> {}, budget);
            var tools = new PlanningTurn(turn, budget, Set::of, "test-key");
            var provider = new GeminiProvider(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            var input = new ChatProvider.ChatInput("model", "Help with tasks.", List.of(
                    new ChatProvider.Message(ChatProvider.Role.USER, "Help me organize my tasks")));
            var text = new StringBuilder();
            try (var generation = provider.open(input, "test-key", tools)) { generation.stream(text::append); }
            assertThat(text).hasToString(selectPlan ? "" : "Let's work through your tasks.");
            assertThat(requests).hasSize(1);
            assertThat(requests.getFirst()).contains("plan.stage", "functionDeclarations", "maxLength");
            if (selectPlan) {
                assertThat(tools.stagedPlan()).isNotNull();
                assertThat(requests.getFirst()).contains("plan.stage");
            } else assertThat(tools.stagedPlan()).isNull();
        } finally { server.stop(0); }
    }
}
