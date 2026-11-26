package com.chomiii.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chomiii.provider.ChatProvider;
import com.chomiii.tools.ToolDefinition;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class EvaluationToolBudgetTest {
    @Test
    void aContinuationCannotExceedTheLiveProviderBudget() {
        var requests = new AtomicInteger();
        ChatProvider delegate = new ChatProvider() {
            @Override public String id() { return "gemini"; }
            @Override public Generation open(ChatInput input, String key) { throw new AssertionError(); }
            @Override public Generation open(ChatInput input, String key, Tools tools) {
                requests.incrementAndGet();
                return new Generation() {
                    @Override public void stream(Consumer<String> delta) {
                        tools.beforeContinuation(100);
                        requests.incrementAndGet();
                    }
                    @Override public void close() {}
                };
            }
        };
        ChatProvider.Tools tools = new ChatProvider.Tools() {
            @Override public List<ToolDefinition> definitions() { return List.of(); }
            @Override public Map<String, Object> invoke(String name, Map<String, Object> arguments) { return Map.of(); }
        };
        var budget = new LiveBudget(true, 1);
        try (var timer = Executors.newSingleThreadScheduledExecutor()) {
            var provider = new EvaluationProvider(Map.of(), Map.of(), delegate, budget, timer, 10);
            var input = new ChatProvider.ChatInput("model", "instructions", List.of(
                    new ChatProvider.Message(ChatProvider.Role.USER, "hello")));
            try (var generation = provider.open(input, "test-key", tools)) {
                assertThatThrownBy(() -> generation.stream(ignored -> {})).isInstanceOf(LiveBudget.Exhausted.class);
            }
            assertThat(requests).hasValue(1);
            assertThat(budget.counts()).containsEntry(LiveBudget.Kind.CHAT, 1)
                    .doesNotContainKey(LiveBudget.Kind.TOOL_CONTINUATION);
        }
    }
}
