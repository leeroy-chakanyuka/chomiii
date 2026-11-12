package com.chomiii.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chomiii.memory.MemoryLearningService;
import com.chomiii.memory.MemoryService;
import com.chomiii.provider.ChatProvider;
import com.chomiii.provider.ProviderCatalog;
import com.chomiii.provider.ProviderFailure;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Background learning must respect the memory revision captured at the
 * originating turn, and optional memory failures must never block a chat.
 */
class ChatMemoryLearningTest {
    private static final String OWNER = "owner";
    private static final String TURN_TEXT = "My usual groceries are oats and milk.";
    private static final String EXTRACTION_JSON = "{\"memories\":[{\"content\":\"My usual groceries are"
            + " oats and milk\",\"kind\":\"fact\",\"confidence\":0.96,"
            + "\"evidence\":\"My usual groceries are oats and milk\"}]}";

    private final AtomicLong revision = new AtomicLong(7L);
    private final AtomicLong learnedWithRevision = new AtomicLong(-1L);
    private final CountDownLatch learned = new CountDownLatch(1);
    private final CountDownLatch streamStarted = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch chatClosed = new CountDownLatch(1);
    private final AtomicInteger openCount = new AtomicInteger();
    private final AtomicReference<ChatProvider.ChatInput> chatInput = new AtomicReference<>();
    private MemoryService memories;
    private ChatStreamService chat;
    private ExecutorService chatWorker;
    private ExecutorService learningWorker;
    private ScheduledExecutorService learningTimer;
    private UUID conversationId;

    @BeforeEach
    void setUp() {
        memories = mock(MemoryService.class);
        when(memories.forChat(anyString(), anyString())).thenReturn("");
        when(memories.revision(OWNER)).thenAnswer(ignored -> revision.get());
        doAnswer(invocation -> {
            learnedWithRevision.set(invocation.getArgument(2, Long.class));
            learned.countDown();
            return null;
        }).when(memories).learn(anyString(), any(), anyLong(), anyString(), anyString(), anyDouble(), any());
    }

    @AfterEach
    void tearDown() {
        chatWorker.shutdownNow();
        learningWorker.shutdownNow();
        learningTimer.shutdownNow();
    }

    @Test
    void learningUsesTheRevisionCapturedAtTurnStartEvenIfTheUserMutatesMemoriesWhileStreaming()
            throws Exception {
        chat = chat(turnStore(), blockingChatProvider());
        chat.start(conversation(), OWNER, request(), "test-key");
        assertThat(streamStarted.await(10, TimeUnit.SECONDS)).isTrue();
        // The user deletes or corrects a memory while the reply is still streaming;
        // the memory revision moves from 7 to 8 before the reply even completes.
        revision.set(8L);
        release.countDown();
        assertThat(learned.await(10, TimeUnit.SECONDS)).isTrue();
        // The learning claim must be evaluated against the turn-start revision (7);
        // a fresh post-reply read (8) would authorize recreating the corrected fact.
        assertThat(learnedWithRevision.get()).isEqualTo(7L);
    }

    @Test
    void aMemoryMutationOverlappingTurnBeginInvalidatesThisTurnsLearning() throws Exception {
        // A concurrent deletion or clear commits while store.begin is persisting
        // the user's message, before this turn's revision baseline could be read.
        ChatTurnStore store = mock(ChatTurnStore.class);
        when(store.begin(any(), anyString(), any(), anyString())).thenAnswer(invocation -> {
            revision.set(8L);
            return new ChatTurnStore.Pending(UUID.randomUUID(), List.of());
        });
        chat = chat(store, blockingChatProvider());
        chat.start(conversation(), OWNER, request(), "test-key");
        assertThat(streamStarted.await(10, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        assertThat(learned.await(10, TimeUnit.SECONDS)).isTrue();
        // The baseline predates the mutation, so the claim is stale and the store
        // discards it instead of recreating the corrected memory.
        assertThat(learnedWithRevision.get()).isEqualTo(7L);
    }

    @Test
    void aTransactionFailureReadingRevisionNeverBlocksTheCompletedChat() throws Exception {
        when(memories.revision(OWNER))
                .thenThrow(new CannotCreateTransactionException("connection pool exhausted"));
        chat = chat(turnStore(), blockingChatProvider());
        chat.start(conversation(), OWNER, request(), "test-key");
        assertThat(streamStarted.await(10, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        assertThat(chatClosed.await(10, TimeUnit.SECONDS)).isTrue();
        // Chat streamed and closed normally; without a revision baseline the turn
        // leaves no pending learning claim and makes no extraction request.
        assertThat(openCount.get()).isEqualTo(1);
        verify(memories, after(1_000).never()).learn(anyString(), any(), anyLong(),
                anyString(), anyString(), anyDouble(), any());
    }

    @Test
    void aTransactionFailureDuringRetrievalNeverBlocksTheCompletedChat() throws Exception {
        when(memories.forChat(anyString(), anyString()))
                .thenThrow(new CannotCreateTransactionException("connection pool exhausted"));
        CatalogProvider provider = new CatalogProvider(input -> {
            openCount.incrementAndGet();
            if (input.systemInstruction().contains("memory-extraction")) {
                return extractionGeneration();
            }
            chatInput.set(input);
            return chatGeneration(delta -> delta.accept("Answered without saved memories."));
        });
        chat = chat(turnStore(), provider);
        chat.start(conversation(), OWNER, request(), "test-key");
        assertThat(chatClosed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(chatInput.get().messages()).containsExactly(
                new ChatProvider.Message(ChatProvider.Role.USER, TURN_TEXT));
        // Retrieval failure does not invalidate the turn's revision baseline.
        assertThat(learned.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(learnedWithRevision.get()).isEqualTo(7L);
    }

    @Test
    void aTurnInterruptedByTheUserNeverSchedulesLearning() throws Exception {
        chat = chat(turnStore(), blockingChatProvider());
        chat.start(conversation(), OWNER, request(), "test-key");
        assertThat(streamStarted.await(10, TimeUnit.SECONDS)).isTrue();
        chat.cancelConversation(conversation(), OWNER);
        assertThat(chatClosed.await(10, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        verify(memories, after(1_000).never()).learn(anyString(), any(), anyLong(),
                anyString(), anyString(), anyDouble(), any());
    }

    @Test
    void aTurnThatFailsInProviderStreamingNeverSchedulesLearning() throws Exception {
        CatalogProvider provider = new CatalogProvider(input -> {
            openCount.incrementAndGet();
            if (input.systemInstruction().contains("memory-extraction")) {
                return extractionGeneration();
            }
            chatInput.set(input);
            return chatGeneration(delta -> {
                delta.accept("unrecoverable ");
                throw new ProviderFailure(ProviderFailure.Code.RATE_LIMITED, "Rate limited");
            });
        });
        chat = chat(turnStore(), provider);
        chat.start(conversation(), OWNER, request(), "test-key");
        verify(memories, after(1_000).never()).learn(anyString(), any(), anyLong(),
                anyString(), anyString(), anyDouble(), any());
    }

    private ChatStreamService chat(ChatTurnStore store, CatalogProvider provider) {
        chatWorker = Executors.newSingleThreadExecutor();
        learningWorker = Executors.newSingleThreadExecutor();
        learningTimer = Executors.newSingleThreadScheduledExecutor();
        var learning = new MemoryLearningService(memories, new ObjectMapper(), learningWorker, learningTimer);
        return new ChatStreamService(store, conversations(), memories, learning,
                new ProviderCatalog(List.of("gemini-3.5-flash-lite")),
                List.of(provider.asChatProvider()), chatWorker);
    }

    private CatalogProvider blockingChatProvider() {
        return new CatalogProvider(input -> {
            openCount.incrementAndGet();
            if (input.systemInstruction().contains("memory-extraction")) {
                return extractionGeneration();
            }
            chatInput.set(input);
            return chatGeneration(delta -> {
                streamStarted.countDown();
                delta.accept("Sure, noted.");
                awaitIndefinitely(release);
            });
        });
    }

    private ChatTurnStore turnStore() {
        ChatTurnStore store = mock(ChatTurnStore.class);
        when(store.begin(any(), anyString(), any(), anyString()))
                .thenReturn(new ChatTurnStore.Pending(UUID.randomUUID(), List.of()));
        return store;
    }

    private ConversationRepository conversations() {
        return mock(ConversationRepository.class);
    }

    private UUID conversation() {
        if (conversationId == null) conversationId = UUID.randomUUID();
        return conversationId;
    }

    private ChatStreamController.MessageRequest request() {
        return new ChatStreamController.MessageRequest(UUID.randomUUID(), "gemini",
                "gemini-3.5-flash-lite", TURN_TEXT);
    }

    private ChatProvider.Generation chatGeneration(Consumer<Consumer<String>> behaviour) {
        return new ChatProvider.Generation() {
            @Override public void stream(Consumer<String> delta) { behaviour.accept(delta); }
            @Override public void close() { chatClosed.countDown(); }
        };
    }

    private ChatProvider.Generation extractionGeneration() {
        return new ChatProvider.Generation() {
            @Override public void stream(Consumer<String> delta) { delta.accept(EXTRACTION_JSON); }
            @Override public void close() { }
        };
    }

    private static void awaitIndefinitely(CountDownLatch latch) {
        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        }
    }

    /** Distinguishes the chat request from the background extraction request. */
    private record CatalogProvider(java.util.function.Function<ChatProvider.ChatInput,
            ChatProvider.Generation> behaviour) {
        ChatProvider asChatProvider() {
            return new ChatProvider() {
                @Override public String id() { return "gemini"; }
                @Override public Generation open(ChatInput input, String key) { return behaviour.apply(input); }
            };
        }
    }
}
