package com.chomiii.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chomiii.provider.ChatProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class MemoryLearningServiceTest {
    @Test
    void learnsAfterSchedulingWithoutBlockingTheCaller() throws Exception {
        MemoryService memories = mock(MemoryService.class);
        when(memories.revision("owner")).thenReturn(5L);
        CountDownLatch learned = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            learned.countDown();
            return null;
        }).when(memories).learn(eq("owner"), any(), eq(5L),
                eq("My usual groceries are oats and milk"), eq("conversation_fact"), eq(0.96));
        AtomicReference<ChatProvider.ChatInput> request = new AtomicReference<>();
        ChatProvider provider = new ChatProvider() {
            @Override public String id() { return "gemini"; }
            @Override public Generation open(ChatInput input, String key) {
                request.set(input);
                return new Generation() {
                    @Override public void stream(Consumer<String> delta) {
                        delta.accept("{\"memories\":[{\"content\":\"My usual groceries are oats and milk\","
                                + "\"kind\":\"fact\",\"confidence\":0.96,"
                                + "\"evidence\":\"My usual groceries are oats and milk\"}]}");
                    }
                    @Override public void close() {}
                };
            }
        };
        try (var worker = Executors.newSingleThreadExecutor()) {
            var service = new MemoryLearningService(memories, new ObjectMapper(), worker);
            service.schedule("owner", UUID.randomUUID(),
                    "My usual groceries are oats and milk.", provider, "gemini-3.5-flash-lite", "fake-key");
            assertThat(learned.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(request.get().systemInstruction()).contains("conservative memory-extraction");
            verify(memories).learn(eq("owner"), any(), eq(5L),
                    eq("My usual groceries are oats and milk"), eq("conversation_fact"), eq(0.96));
        }
    }

    @Test
    void genericMessagesDoNotConsumeAnExtraModelRequest() {
        MemoryService memories = mock(MemoryService.class);
        ChatProvider provider = mock(ChatProvider.class);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var service = new MemoryLearningService(memories, new ObjectMapper(), worker);
            service.schedule("owner", UUID.randomUUID(), "Explain how sorting works.",
                    provider, "gemini-3.5-flash-lite", "fake-key");
            org.mockito.Mockito.verify(provider, org.mockito.Mockito.never()).open(any(), any());
            org.mockito.Mockito.verify(memories, org.mockito.Mockito.never()).revision(any());
        }
    }
}
