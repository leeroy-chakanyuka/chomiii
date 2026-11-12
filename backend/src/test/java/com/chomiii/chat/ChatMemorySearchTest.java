package com.chomiii.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chomiii.memory.MemoryLearningService;
import com.chomiii.memory.MemoryService;
import com.chomiii.memory.PersonalMemory;
import com.chomiii.provider.ChatProvider;
import com.chomiii.provider.ProviderCatalog;
import com.chomiii.tools.InternalTool;
import com.chomiii.tools.ToolDefinition;
import com.chomiii.tools.ToolExecutionContext;
import com.chomiii.tools.ToolRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ChatMemorySearchTest {
    @Test
    void modelCanSearchAndFinishTheOriginalTurn() throws Exception {
        var store = mock(ChatTurnStore.class);
        when(store.begin(any(), anyString(), any(), anyString()))
                .thenReturn(new ChatTurnStore.Pending(UUID.randomUUID(), List.of()));
        var memories = mock(MemoryService.class);
        when(memories.forChat(anyString(), anyString())).thenReturn("");
        var fact = new PersonalMemory(UUID.randomUUID(), "I prefer tacos", "user_explicit", Instant.now(), Instant.now());
        when(memories.search("owner", "dinner", null, 5)).thenReturn(List.of(fact));
        var result = new AtomicReference<Map<String, Object>>();
        var completed = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call -> { completed.countDown(); return null; })
                .when(store).finish(any(), anyString(), any(), any(), anyString(), anyString());
        ChatProvider provider = new ChatProvider() {
            @Override public String id() { return "gemini"; }
            @Override public Generation open(ChatInput input, String key) { throw new AssertionError("Tool was not supplied"); }
            @Override public Generation open(ChatInput input, String key, Tools tools) {
                return new Generation() {
                    @Override public void stream(Consumer<String> delta) {
                        result.set(tools.invoke("memory.search", Map.of("query", "dinner")));
                        delta.accept("You prefer tacos.");
                    }
                    @Override public void close() {}
                };
            }
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var chat = new ChatStreamService(store, mock(ConversationRepository.class), memories,
                    mock(MemoryLearningService.class), new ProviderCatalog(List.of("gemini-3.5-flash-lite")),
                    List.of(provider), executor);
            var conversation = UUID.randomUUID();
            var request = UUID.randomUUID();
            chat.start(conversation, "owner", new ChatStreamController.MessageRequest(
                    request, "gemini", "gemini-3.5-flash-lite", "What should I eat?"), "test-key");
            assertThat(completed.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(result.get().toString()).contains("I prefer tacos");
            org.mockito.Mockito.verify(store).finish(org.mockito.ArgumentMatchers.eq(conversation),
                    org.mockito.ArgumentMatchers.eq("owner"), org.mockito.ArgumentMatchers.eq(request),
                    any(), org.mockito.ArgumentMatchers.eq("You prefer tacos."), org.mockito.ArgumentMatchers.eq("complete"));
        }
    }

    @Test
    void disablingRecallDoesNotSupplyAFunction() throws Exception {
        var store = mock(ChatTurnStore.class);
        when(store.begin(any(), anyString(), any(), anyString()))
                .thenReturn(new ChatTurnStore.Pending(UUID.randomUUID(), List.of()));
        var memories = mock(MemoryService.class);
        var opened = new CountDownLatch(1);
        ChatProvider provider = new ChatProvider() {
            @Override public String id() { return "gemini"; }
            @Override public Generation open(ChatInput input, String key) {
                opened.countDown();
                return new Generation() {
                    @Override public void stream(Consumer<String> delta) { delta.accept("Hello."); }
                    @Override public void close() {}
                };
            }
            @Override public Generation open(ChatInput input, String key, Tools tools) {
                throw new AssertionError("Recall-disabled turn exposed a tool");
            }
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var chat = new ChatStreamService(store, mock(ConversationRepository.class), memories,
                    mock(MemoryLearningService.class), new ProviderCatalog(List.of("gemini-3.5-flash-lite")),
                    List.of(provider), executor);
            chat.start(UUID.randomUUID(), "owner", new ChatStreamController.MessageRequest(
                    UUID.randomUUID(), "gemini", "gemini-3.5-flash-lite", "Hello"), "test-key", false);
            assertThat(opened.await(20, TimeUnit.SECONDS)).isTrue();
            org.mockito.Mockito.verify(memories, org.mockito.Mockito.never()).forChat(anyString(), anyString());
            org.mockito.Mockito.verify(memories, org.mockito.Mockito.never())
                    .search(anyString(), anyString(), any(), org.mockito.ArgumentMatchers.anyInt());
        }
    }

    @Test
    void toolCanBeDisabledWhileAutomaticRecallRemainsEnabled() throws Exception {
        var store = mock(ChatTurnStore.class);
        when(store.begin(any(), anyString(), any(), anyString()))
                .thenReturn(new ChatTurnStore.Pending(UUID.randomUUID(), List.of()));
        var memories = mock(MemoryService.class);
        when(memories.forChat("owner", "Hello")).thenReturn("Saved profile: I live in Harare.");
        var captured = new AtomicReference<ChatProvider.ChatInput>();
        var opened = new CountDownLatch(1);
        ChatProvider provider = new ChatProvider() {
            @Override public String id() { return "gemini"; }
            @Override public Generation open(ChatInput input, String key) {
                captured.set(input);
                opened.countDown();
                return new Generation() {
                    @Override public void stream(Consumer<String> delta) { delta.accept("Hello."); }
                    @Override public void close() {}
                };
            }
            @Override public Generation open(ChatInput input, String key, Tools tools) {
                throw new AssertionError("Disabled tool was supplied");
            }
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var chat = new ChatStreamService(store, mock(ConversationRepository.class), memories,
                    mock(MemoryLearningService.class), new ProviderCatalog(List.of("gemini-3.5-flash-lite")),
                    List.of(provider), executor, false);
            chat.start(UUID.randomUUID(), "owner", new ChatStreamController.MessageRequest(
                    UUID.randomUUID(), "gemini", "gemini-3.5-flash-lite", "Hello"), "test-key");
            assertThat(opened.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(captured.get().messages()).extracting(ChatProvider.Message::text)
                    .contains("Saved profile: I live in Harare.");
        }
    }

    @Test
    void planningCanCiteMemoryReturnedByTheTool() throws Exception {
        var store = mock(ChatTurnStore.class);
        when(store.begin(any(), anyString(), any(), anyString()))
                .thenReturn(new ChatTurnStore.Pending(UUID.randomUUID(), List.of()));
        var memories = mock(MemoryService.class);
        when(memories.forChat(anyString(), anyString())).thenReturn("");
        when(memories.forPlanning(anyString(), anyString())).thenReturn(List.of());
        var fact = new PersonalMemory(UUID.randomUUID(), "I prefer tacos", "user_explicit", Instant.now(), Instant.now());
        when(memories.search("owner", "dinner", null, 5)).thenReturn(List.of(fact));
        var completed = new CountDownLatch(1);
        var savedStatus = new AtomicReference<String>();
        org.mockito.Mockito.doAnswer(call -> {
            savedStatus.set(call.getArgument(5));
            completed.countDown();
            return null;
        }).when(store).finish(any(), anyString(), any(), any(), anyString(), anyString());
        ChatProvider provider = new ChatProvider() {
            @Override public String id() { return "gemini"; }
            @Override public Generation open(ChatInput input, String key) { throw new AssertionError("Tool was not supplied"); }
            @Override public Generation open(ChatInput input, String key, Tools tools) {
                return new Generation() {
                    @Override public void stream(Consumer<String> delta) {
                        assertThat(tools.invoke("memory.search", Map.of("query", "dinner"))).containsKey("output");
                        delta.accept("""
                                {"goal":"Plan dinner","summary":"Prepare a meal.",
                                 "memoryReferences":[{"id":"%s","relevance":"Dinner preference"}],
                                 "constraints":[],"missingInformation":[],
                                 "steps":[{"id":"s1","title":"Prepare dinner","capability":"user.prepare","dependsOn":[]}]}
                                """.formatted(fact.id()));
                    }
                    @Override public void close() {}
                };
            }
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var chat = new ChatStreamService(store, mock(ConversationRepository.class), memories,
                    mock(MemoryLearningService.class), new ProviderCatalog(List.of("gemini-3.5-flash-lite")),
                    List.of(provider), executor);
            chat.start(UUID.randomUUID(), "owner", new ChatStreamController.MessageRequest(
                    UUID.randomUUID(), "gemini", "gemini-3.5-flash-lite", "Plan dinner", true), "test-key");
            assertThat(completed.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(savedStatus.get()).isEqualTo("complete");
        }
    }

    @Test
    void aRegisteredReadToolWorksWithoutProviderSpecificExecutionCode() throws Exception {
        var store = mock(ChatTurnStore.class);
        when(store.begin(any(), anyString(), any(), anyString()))
                .thenReturn(new ChatTurnStore.Pending(UUID.randomUUID(), List.of()));
        var invoked = new CountDownLatch(1);
        InternalTool tool = new InternalTool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition("example.read", "Read a test value", Map.of("type", "object"),
                        "example.read", ToolDefinition.Effect.READ);
            }
            @Override public Result execute(ToolExecutionContext context, Map<String, Object> arguments) {
                assertThat(context.owner()).isEqualTo("owner-a");
                invoked.countDown();
                return new Result(Map.of("value", "available"), () -> {});
            }
        };
        ChatProvider provider = new ChatProvider() {
            @Override public String id() { return "gemini"; }
            @Override public Generation open(ChatInput input, String key) { throw new AssertionError("Tool was not supplied"); }
            @Override public Generation open(ChatInput input, String key, Tools tools) {
                assertThat(tools.definitions()).extracting(ToolDefinition::name).containsExactly("example.read");
                return new Generation() {
                    @Override public void stream(Consumer<String> delta) {
                        assertThat(tools.invoke("example.read", Map.of())).containsKey("output");
                        delta.accept("Available.");
                    }
                    @Override public void close() { }
                };
            }
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var chat = new ChatStreamService(store, mock(ConversationRepository.class), mock(MemoryService.class),
                    mock(MemoryLearningService.class), new ProviderCatalog(List.of("gemini-3.5-flash-lite")),
                    List.of(provider), executor, true, null, null, new ToolRegistry(List.of(tool)));
            chat.start(UUID.randomUUID(), "owner-a", new ChatStreamController.MessageRequest(
                    UUID.randomUUID(), "gemini", "gemini-3.5-flash-lite", "Hello"), "test-key", false);
            assertThat(invoked.await(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void cancellingChatInterruptsTheRegisteredToolAndDoesNotCompleteTheReply() throws Exception {
        UUID conversation = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        var store = mock(ChatTurnStore.class);
        when(store.begin(any(), anyString(), any(), anyString()))
                .thenReturn(new ChatTurnStore.Pending(UUID.randomUUID(), List.of()));
        var conversations = mock(ConversationRepository.class);
        when(conversations.find(conversation, "owner"))
                .thenReturn(java.util.Optional.of(new ConversationDto.Summary(conversation, "Chat", Instant.EPOCH, Instant.EPOCH)));
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var status = new AtomicReference<String>();
        org.mockito.Mockito.doAnswer(call -> { status.set(call.getArgument(5)); finished.countDown(); return null; })
                .when(store).finish(any(), anyString(), any(), any(), anyString(), anyString());
        InternalTool slow = new InternalTool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition("example.read", "Read a test value", Map.of("type", "object"), "example.read", ToolDefinition.Effect.READ);
            }
            @Override public Result execute(ToolExecutionContext context, Map<String, Object> arguments) {
                entered.countDown();
                try { Thread.sleep(30_000); }
                catch (InterruptedException cancelled) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                return new Result(Map.of(), () -> { });
            }
        };
        ChatProvider provider = new ChatProvider() {
            @Override public String id() { return "gemini"; }
            @Override public Generation open(ChatInput input, String key) { throw new AssertionError(); }
            @Override public Generation open(ChatInput input, String key, Tools tools) {
                return new Generation() {
                    @Override public void stream(Consumer<String> delta) {
                        tools.invoke("example.read", Map.of());
                        delta.accept("Should not complete.");
                    }
                    @Override public void close() { }
                };
            }
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var chat = new ChatStreamService(store, conversations, mock(MemoryService.class), mock(MemoryLearningService.class),
                    new ProviderCatalog(List.of("gemini-3.5-flash-lite")), List.of(provider), executor,
                    true, null, null, new ToolRegistry(List.of(slow)));
            chat.start(conversation, "owner", new ChatStreamController.MessageRequest(
                    request, "gemini", "gemini-3.5-flash-lite", "Hello"), "test-key", false);
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            chat.cancel(conversation, "owner", request);
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(status.get()).isEqualTo("interrupted");
        }
    }
}
