package com.chomiii.provider.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import com.chomiii.provider.ChatProvider;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GeminiMemoryToolTest {
    @Test
    void nativeFunctionCallReturnsAResultAndContinuesTheSameModelTurn() throws Exception {
        var requests = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String response = requests.size() == 1
                    ? "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"id\":\"call-1\",\"name\":\"memory.search\",\"args\":{\"query\":\"dinner\"}},\"thoughtSignature\":\"YWJj\"}]}}]}\n\n"
                    : "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"You like tacos.\"}]}}]}\n\n";
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write(response.getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        try {
            var provider = new GeminiProvider(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            var input = new ChatProvider.ChatInput("gemini-3.5-flash-lite", "Be helpful.", List.of(
                    new ChatProvider.Message(ChatProvider.Role.USER, "What should I eat?")));
            var calls = new ArrayList<Map<String, Object>>();
            ChatProvider.Tools tools = new ChatProvider.Tools() {
                @Override public List<ChatProvider.ToolSpec> definitions() {
                    return List.of(new ChatProvider.ToolSpec("memory.search", "Search saved memories",
                            Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string")),
                                    "required", List.of("query"))));
                }
                @Override public Map<String, Object> invoke(String name, Map<String, Object> arguments) {
                    assertThat(name).isEqualTo("memory.search");
                    calls.add(arguments);
                    return Map.of("memories", List.of(Map.of("content", "I like tacos")));
                }
            };
            var answer = new StringBuilder();
            try (var generation = provider.open(input, "test-key", tools)) {
                generation.stream(answer::append);
            }

            assertThat(answer).hasToString("You like tacos.");
            assertThat(calls).containsExactly(Map.of("query", "dinner"));
            assertThat(requests).hasSize(2);
            assertThat(requests.get(0)).contains("memory.search", "functionDeclarations");
            assertThat(requests.get(1)).contains("functionCall", "functionResponse", "I like tacos", "YWJj", "call-1");
        } finally {
            server.stop(0);
        }
    }
}
