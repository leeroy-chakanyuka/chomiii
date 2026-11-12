package com.chomiii.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chomiii.memory.MemoryLearningService;
import com.chomiii.memory.MemoryService;
import com.chomiii.provider.ChatProvider;
import com.chomiii.provider.ProviderCatalog;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ChatTimeToolTest {
    @Test
    void timeToolIsAvailableEvenWhenPersonalMemoryRecallIsDisabled() throws Exception {
        var store = mock(ChatTurnStore.class);
        when(store.begin(any(), anyString(), any(), anyString()))
                .thenReturn(new ChatTurnStore.Pending(UUID.randomUUID(), List.of()));
        var completed = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call -> { completed.countDown(); return null; })
                .when(store).finish(any(), anyString(), any(), any(), anyString(), anyString());

        var result = new AtomicReference<Map<String, Object>>();
        ChatProvider provider = new ChatProvider() {
            @Override public String id() { return "gemini"; }
            @Override public Generation open(ChatInput input, String key) {
                throw new AssertionError("An enabled time tool should be offered");
            }
            @Override public Generation open(ChatInput input, String key, Tools tools) {
                return new Generation() {
                    @Override public void stream(Consumer<String> delta) {
                        assertThat(tools.definitions()).extracting(spec -> spec.name()).containsExactly("time.now");
                        result.set(tools.invoke("time.now", Map.of("zone", "UTC")));
                        delta.accept("The time was checked.");
                    }
                    @Override public void close() {}
                };
            }
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var chat = new ChatStreamService(store, mock(ConversationRepository.class),
                    mock(MemoryService.class), mock(MemoryLearningService.class),
                    new ProviderCatalog(List.of("gemini-3.5-flash-lite")),
                    List.of(provider), executor, true, null, null, true);
            chat.start(UUID.randomUUID(), "owner", new ChatStreamController.MessageRequest(
                    UUID.randomUUID(), "gemini", "gemini-3.5-flash-lite", "What time is it?"),
                    "test-key", false);
            assertThat(completed.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(result.get()).containsKey("output");
        }
    }
}
