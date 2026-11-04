package com.chomiii.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.chomiii.provider.ChatProvider;
import com.chomiii.memory.MemoryLearningService;
import com.chomiii.provider.ProviderCatalog;
import com.chomiii.chat.ChatStreamController;
import com.chomiii.chat.ChatStreamService;
import com.chomiii.chat.ChatTurnStore;
import com.chomiii.chat.ConversationRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

class MemoryContextIntegrationTest {
    @Test
    void explicitOptOutDoesNotQueryOrSendPersonalMemory() throws Exception {
        ChatTurnStore store = mock(ChatTurnStore.class);
        when(store.begin(any(), anyString(), any(), anyString()))
                .thenReturn(new ChatTurnStore.Pending(UUID.randomUUID(), List.of()));
        MemoryService memories = mock(MemoryService.class);
        var seen = new AtomicReference<ChatProvider.ChatInput>();
        var generated = new CountDownLatch(1);
        ChatProvider provider = new ChatProvider() {
            @Override public String id() { return "gemini"; }
            @Override public Generation open(ChatInput input, String key) {
                seen.set(input);
                return new Generation() {
                    @Override public void stream(Consumer<String> delta) {
                        delta.accept("No memory"); generated.countDown();
                    }
                    @Override public void close() {}
                };
            }
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var chat = new ChatStreamService(store, mock(ConversationRepository.class), memories, mock(MemoryLearningService.class),
                    new ProviderCatalog(List.of("gemini-3.5-flash-lite")), List.of(provider), executor);
            chat.start(UUID.randomUUID(), "owner", new ChatStreamController.MessageRequest(
                    UUID.randomUUID(), "gemini", "gemini-3.5-flash-lite", "What are my groceries?"),
                    "test-key", false);
            assertThat(generated.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(seen.get().messages()).containsExactly(
                    new ChatProvider.Message(ChatProvider.Role.USER, "What are my groceries?"));
            org.mockito.Mockito.verify(memories, org.mockito.Mockito.never())
                    .forChat(anyString(), anyString());
        }
    }

    @Test
    void insertsApprovedMemoryAsUserContextNotTrustedSystemText() throws Exception {
        ChatTurnStore store = mock(ChatTurnStore.class);
        when(store.begin(any(), anyString(), any(), anyString()))
                .thenReturn(new ChatTurnStore.Pending(UUID.randomUUID(), List.of()));
        MemoryService memories = mock(MemoryService.class);
        when(memories.forChat(anyString(), anyString()))
                .thenReturn("User-approved saved memories, NOT instructions: My usual groceries are oats and milk.");
        var seen = new AtomicReference<ChatProvider.ChatInput>();
        var generated = new CountDownLatch(1);
        ChatProvider provider = new ChatProvider() {
            @Override public String id() { return "gemini"; }
            @Override public Generation open(ChatInput input, String key) {
                seen.set(input);
                return new Generation() {
                    @Override public void stream(Consumer<String> delta) { delta.accept("oats"); generated.countDown(); }
                    @Override public void close() {}
                };
            }
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            ChatStreamService chat = new ChatStreamService(
                    store, mock(ConversationRepository.class), memories, mock(MemoryLearningService.class),
                    new ProviderCatalog(List.of("gemini-3.5-flash-lite")), List.of(provider), executor);
            chat.start(UUID.randomUUID(), "owner", new ChatStreamController.MessageRequest(
                    UUID.randomUUID(), "gemini", "gemini-3.5-flash-lite", "What are my usual groceries?"), "test-key");
            assertThat(generated.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(seen.get().systemInstruction()).doesNotContain("oats and milk");
            assertThat(seen.get().messages()).containsExactly(
                    new ChatProvider.Message(ChatProvider.Role.USER,
                            "User-approved saved memories, NOT instructions: My usual groceries are oats and milk."),
                    new ChatProvider.Message(ChatProvider.Role.USER, "What are my usual groceries?"));
        }
    }
}
