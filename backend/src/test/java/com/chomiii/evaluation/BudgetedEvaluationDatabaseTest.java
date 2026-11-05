package com.chomiii.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.chomiii.provider.ChatProvider;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfEnvironmentVariable(named = "EVAL_DISPOSABLE_DB", matches = "true")
@EnabledIfSystemProperty(named = "eval.mode", matches = "integration|live")
class BudgetedEvaluationDatabaseTest {
    @Test
    void exhaustedBudgetStopsFollowUpBeforeOpeningTheProvider() throws Exception {
        var fixture = new FixtureLoader().load(Path.of("..", "evals", "fixtures", "m2", "explicit-facts", "appointments.json"));
        var calls = new AtomicInteger();
        ChatProvider provider = new ChatProvider() {
            @Override public String id() { return "gemini"; }
            @Override public Generation open(ChatInput input, String key) {
                calls.incrementAndGet();
                boolean extraction = input.systemInstruction().contains("memory-extraction classifier");
                return new Generation() {
                    @Override public void stream(Consumer<String> delta) {
                        delta.accept(extraction ? """
                                {"memories":[{"content":"I prefer appointments after ten in the morning",
                                "kind":"fact","confidence":0.95,"evidence":"I prefer appointments after ten in the morning"}]}
                                """ : "Acknowledged.");
                    }
                    @Override public void close() { }
                };
            }
        };
        var budget = new LiveBudget(true, 2);
        try (var database = DisposableEvaluationDatabase.start()) {
            var result = new MemoryEvaluationRunner().runDatabase(fixture, database, provider, budget,
                    "gemini-3.5-flash-lite", "fake-test-key", 10);
            assertThat(result.memories()).hasSize(1);
            assertThat(result.retrievals().getFirst().failure()).isEqualTo("Exhausted");
            assertThat(calls).hasValue(2);
            assertThat(budget.counts()).containsEntry(LiveBudget.Kind.CHAT, 1)
                    .containsEntry(LiveBudget.Kind.EXTRACTION, 1).doesNotContainKey(LiveBudget.Kind.FOLLOW_UP);
        }
    }
}
