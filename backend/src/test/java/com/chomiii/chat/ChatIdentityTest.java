package com.chomiii.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chomiii.provider.ChatProvider;
import com.chomiii.provider.ProviderCatalog;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ChatIdentityTest {
    @Test
    void passesAConsistentChomiiiIdentitySeparatelyFromUserText() throws Exception {
        ChatTurnStore store = mock(ChatTurnStore.class);
        when(store.begin(any(), anyString(), any(), anyString()))
                .thenReturn(new ChatTurnStore.Pending(UUID.randomUUID(), List.of()));
        var captured = new AtomicReference<ChatProvider.ChatInput>();
        var received = new CountDownLatch(1);
        ChatProvider provider = new ChatProvider() {
            @Override public String id() { return "gemini"; }
            @Override public Generation open(ChatInput input, String key) {
                captured.set(input);
                return new Generation() {
                    @Override public void stream(Consumer<String> delta) { delta.accept("Hello"); received.countDown(); }
                    @Override public void close() {}
                };
            }
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var chat = new ChatStreamService(store, mock(ConversationRepository.class),
                    new ProviderCatalog(List.of("gemini-3.5-flash-lite")), List.of(provider), executor);
            chat.start(UUID.randomUUID(), "session", new ChatStreamController.MessageRequest(
                    UUID.randomUUID(), "gemini", "gemini-3.5-flash-lite", "Hello"), "test-key");
            assertThat(received.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(captured.get().systemInstruction()).contains("Chomiii", "warm", "no tools");
            assertThat(captured.get().messages()).containsExactly(new ChatProvider.Message(ChatProvider.Role.USER, "Hello"));
        }
    }
}
