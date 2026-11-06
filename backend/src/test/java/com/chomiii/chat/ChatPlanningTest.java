package com.chomiii.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chomiii.memory.MemoryLearningService;
import com.chomiii.memory.MemoryService;
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

class ChatPlanningTest {
    @Test
    void naturalPlanningRequestPersistsOnlyAValidatedProposal() throws Exception {
        String proposal = """
                {"goal":"Resolve the meeting conflict","summary":"Prepare a proposal.","memoryReferences":[],
                 "constraints":[],"missingInformation":[],"steps":[{"id":"s1","title":"Find the meeting",
                 "capability":"calendar.search","dependsOn":[]}]}
                """;
        var result = run("Please help me plan my meeting tomorrow.", proposal);
        assertThat(result.status()).isEqualTo("complete");
        assertThat(result.content()).contains("Proposed plan", "nothing has been done", "service not connected")
                .doesNotContain("\"capability\"");
        assertThat(result.input().systemInstruction()).contains("proposal").doesNotContain("my meeting tomorrow");
    }

    @Test
    void malformedProposalFailsWithoutPersistingTheModelText() throws Exception {
        var result = run("Please help me plan my meeting tomorrow.", "I already sent the email.");
        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.content()).isEmpty();
    }

    @Test
    void refusesAnotherOwnersConversationBeforeRetrievingMemory() {
        var store = mock(ChatTurnStore.class);
        when(store.begin(any(), anyString(), any(), anyString())).thenThrow(new ConversationNotFoundException());
        var memories = mock(MemoryService.class);
        var provider = mock(ChatProvider.class);
        when(provider.id()).thenReturn("gemini");
        try (var executor = Executors.newSingleThreadExecutor()) {
            var chat = new ChatStreamService(store, mock(ConversationRepository.class), memories, mock(MemoryLearningService.class),
                    new ProviderCatalog(List.of("model")), List.of(provider), executor);
            assertThatThrownBy(() -> chat.start(UUID.randomUUID(), "wrong-owner", new ChatStreamController.MessageRequest(
                    UUID.randomUUID(), "gemini", "model", "Please plan my appointment"), "key"))
                    .isInstanceOf(ConversationNotFoundException.class);
            org.mockito.Mockito.verify(memories, org.mockito.Mockito.never()).forPlanning(anyString(), anyString());
            org.mockito.Mockito.verify(provider, org.mockito.Mockito.never()).open(any(), anyString());
        }
    }

    @Test
    void stoppingAPlanCancelsTheProviderAndDoesNotSaveACompletedPlan() throws Exception {
        UUID conversation = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        var store = mock(ChatTurnStore.class);
        when(store.begin(any(), anyString(), any(), anyString()))
                .thenReturn(new ChatTurnStore.Pending(UUID.randomUUID(), List.of()));
        var entered = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var status = new AtomicReference<String>();
        org.mockito.Mockito.doAnswer(call -> { status.set(call.getArgument(5)); finished.countDown(); return null; })
                .when(store).finish(any(), anyString(), any(), any(), anyString(), anyString());
        var conversations = mock(ConversationRepository.class);
        when(conversations.find(conversation, "owner")).thenReturn(java.util.Optional.of(
                new ConversationDto.Summary(conversation, "Chat", java.time.Instant.EPOCH, java.time.Instant.EPOCH)));
        ChatProvider provider = new ChatProvider() {
            @Override public String id() { return "gemini"; }
            @Override public Generation open(ChatInput input, String key) {
                return new Generation() {
                    @Override public void stream(Consumer<String> delta) {
                        entered.countDown();
                        try { closed.await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    }
                    @Override public void close() { closed.countDown(); }
                };
            }
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var chat = new ChatStreamService(store, conversations, mock(MemoryService.class), mock(MemoryLearningService.class),
                    new ProviderCatalog(List.of("model")), List.of(provider), executor);
            chat.start(conversation, "owner", new ChatStreamController.MessageRequest(
                    request, "gemini", "model", "Please plan my appointment"), "key");
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            chat.cancel(conversation, "owner", request);
            assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(closed.getCount()).isZero();
            assertThat(status.get()).isEqualTo("interrupted");
        }
    }

    private Result run(String request, String answer) throws Exception {
        ChatTurnStore store = mock(ChatTurnStore.class);
        when(store.begin(any(), anyString(), any(), anyString()))
                .thenReturn(new ChatTurnStore.Pending(UUID.randomUUID(), List.of()));
        var input = new AtomicReference<ChatProvider.ChatInput>();
        var content = new AtomicReference<String>();
        var status = new AtomicReference<String>();
        var finished = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call -> {
            content.set(call.getArgument(4));
            status.set(call.getArgument(5));
            finished.countDown();
            return null;
        }).when(store).finish(any(), anyString(), any(), any(), anyString(), anyString());
        ChatProvider provider = new ChatProvider() {
            @Override public String id() { return "gemini"; }
            @Override public Generation open(ChatInput value, String key) {
                input.set(value);
                return new Generation() {
                    @Override public void stream(Consumer<String> delta) { delta.accept(answer); }
                    @Override public void close() {}
                };
            }
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            MemoryService memories = mock(MemoryService.class);
            when(memories.forChat(anyString(), anyString())).thenReturn("");
            var chat = new ChatStreamService(store, mock(ConversationRepository.class), memories,
                    mock(MemoryLearningService.class), new ProviderCatalog(List.of("model")), List.of(provider), executor);
            chat.start(UUID.randomUUID(), "owner", new ChatStreamController.MessageRequest(
                    UUID.randomUUID(), "gemini", "model", request), "key");
            assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
        }
        return new Result(input.get(), content.get(), status.get());
    }

    record Result(ChatProvider.ChatInput input, String content, String status) {}
}
