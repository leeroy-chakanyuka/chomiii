package com.chomiii.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.chomiii.provider.ChatProvider;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LiveBudgetTest {
    @Test
    void defaultOfflineBudgetRefusesProviderCalls() {
        var budget = new LiveBudget(false, 10);
        assertThatThrownBy(() -> budget.reserve(LiveBudget.Kind.CHAT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("disabled");
        assertThat(budget.counts()).isEmpty();
    }

    @Test
    void countsEveryCallTypeBeforeRefusingAnOverBudgetCall() {
        var budget = new LiveBudget(true, 2);
        budget.reserve(LiveBudget.Kind.CHAT);
        budget.reserve(LiveBudget.Kind.EXTRACTION);
        assertThatThrownBy(() -> budget.reserve(LiveBudget.Kind.JUDGE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("budget");
        assertThat(budget.counts()).containsEntry(LiveBudget.Kind.CHAT, 1)
                .containsEntry(LiveBudget.Kind.EXTRACTION, 1).doesNotContainKey(LiveBudget.Kind.JUDGE);
    }

    @Test
    void neverOpensTheRealProviderWhenTheBudgetIsDisabled() {
        ChatProvider delegate = mock(ChatProvider.class);
        try (var timer = Executors.newSingleThreadScheduledExecutor()) {
            var provider = new EvaluationProvider(Map.of(), Map.of(), delegate, new LiveBudget(false, 1), timer, 1);
            var input = new ChatProvider.ChatInput("model", "chat", List.of(new ChatProvider.Message(ChatProvider.Role.USER, "hello")));
            assertThatThrownBy(() -> provider.open(input, "fake-key")).isInstanceOf(IllegalStateException.class);
            verify(delegate, never()).open(any(), any());
            assertThat(provider.calls().getFirst().providerInvoked()).isFalse();
        }
    }

    @Test
    void aProviderReturningAfterCancellationIsStillReportedAsTimedOut() {
        var closed = new CountDownLatch(1);
        ChatProvider delegate = new ChatProvider() {
            @Override public String id() { return "gemini"; }
            @Override public Generation open(ChatInput input, String key) {
                return new Generation() {
                    @Override public void stream(java.util.function.Consumer<String> delta) {
                        try {
                            if (!closed.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timer did not fire");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Interrupted", interrupted);
                        }
                        delta.accept("late reply");
                    }
                    @Override public void close() { closed.countDown(); }
                };
            }
        };
        try (var timer = Executors.newSingleThreadScheduledExecutor()) {
            var provider = new EvaluationProvider(Map.of(), Map.of(), delegate, new LiveBudget(true, 1), timer, 1);
            var input = new ChatProvider.ChatInput("model", "chat", List.of(new ChatProvider.Message(ChatProvider.Role.USER, "hello")));
            try (var generation = provider.open(input, "fake-key")) {
                assertThatThrownBy(() -> generation.stream(text -> {})).isInstanceOf(EvaluationProvider.TimedOut.class);
            }
            assertThat(provider.calls().getFirst().failure()).isEqualTo("TimedOut");
        }
    }
}
