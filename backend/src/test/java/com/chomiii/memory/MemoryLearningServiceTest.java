package com.chomiii.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.chomiii.provider.ChatProvider;
import com.chomiii.provider.ProviderFailure;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class MemoryLearningServiceTest {
    @Test
    void learnsAfterSchedulingWithoutBlockingTheCaller() throws Exception {
        MemoryService memories = mock(MemoryService.class);
        CountDownLatch learned = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            learned.countDown();
            return null;
        }).when(memories).learn(eq("owner"), any(), eq(5L),
                eq("My usual groceries are oats and milk"), eq("conversation_fact"), eq(0.96),
                org.mockito.ArgumentMatchers.isNull());
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
        try (var worker = Executors.newSingleThreadExecutor(); var timer = newTimer()) {
            var service = new MemoryLearningService(memories, new ObjectMapper(), worker, timer);
            service.schedule("owner", UUID.randomUUID(), 5L,
                    "My usual groceries are oats and milk.", provider, "gemini-3.5-flash-lite", "fake-key");
            assertThat(learned.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(request.get().systemInstruction()).contains("conservative memory-extraction");
            verify(memories).learn(eq("owner"), any(), eq(5L),
                    eq("My usual groceries are oats and milk"), eq("conversation_fact"), eq(0.96),
                    org.mockito.ArgumentMatchers.isNull());
        }
    }

    @Test
    void extractionSuppliesSlotTopicsWithoutExtraModelRequests() throws Exception {
        MemoryService memories = mock(MemoryService.class);
        CountDownLatch learned = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            learned.countDown();
            return null;
        }).when(memories).learn(eq("owner"), any(), eq(5L),
                eq("I moved to Bulawayo"), eq("conversation_fact"), eq(0.95), eq("residence"));
        ChatProvider provider = new ChatProvider() {
            @Override public String id() { return "gemini"; }
            @Override public Generation open(ChatInput input, String key) {
                return new Generation() {
                    @Override public void stream(Consumer<String> delta) {
                        delta.accept("{\"memories\":[{\"content\":\"I moved to Bulawayo\","
                                + "\"kind\":\"fact\",\"confidence\":0.95,"
                                + "\"evidence\":\"moved to Bulawayo\",\"topic\":\"residence\"}]}");
                    }
                    @Override public void close() {}
                };
            }
        };
        try (var worker = Executors.newSingleThreadExecutor(); var timer = newTimer()) {
            var service = new MemoryLearningService(memories, new ObjectMapper(), worker, timer);
            service.schedule("owner", UUID.randomUUID(), 5L, "We moved to Bulawayo last month.",
                    provider, "gemini-3.5-flash-lite", "fake-key");
            assertThat(learned.await(10, TimeUnit.SECONDS)).isTrue();
            verify(memories).learn(eq("owner"), any(), eq(5L),
                    eq("I moved to Bulawayo"), eq("conversation_fact"), eq(0.95), eq("residence"));
        }
    }

    @Test
    void genericMessagesDoNotConsumeAnExtraModelRequest() {
        MemoryService memories = mock(MemoryService.class);
        ChatProvider provider = mock(ChatProvider.class);
        try (var worker = Executors.newSingleThreadExecutor(); var timer = newTimer()) {
            var service = new MemoryLearningService(memories, new ObjectMapper(), worker, timer);
            service.schedule("owner", UUID.randomUUID(), 5L, "Explain how sorting works.",
                    provider, "gemini-3.5-flash-lite", "fake-key");
            org.mockito.Mockito.verify(provider, org.mockito.Mockito.never()).open(any(), any());
        }
    }

    @Test
    void aTurnWithoutACapturedRevisionLeavesNoPendingLearningClaim() {
        MemoryService memories = mock(MemoryService.class);
        ChatProvider provider = mock(ChatProvider.class);
        try (var worker = Executors.newSingleThreadExecutor(); var timer = newTimer()) {
            var service = new MemoryLearningService(memories, new ObjectMapper(), worker, timer);
            service.schedule("owner", UUID.randomUUID(), null,
                    "My usual groceries are oats and milk.", provider, "gemini-3.5-flash-lite", "fake-key");
            org.mockito.Mockito.verify(provider, org.mockito.Mockito.never()).open(any(), any());
            org.mockito.Mockito.verify(memories, org.mockito.Mockito.never()).learn(any(), any(), anyLong(),
                    any(), any(), anyDouble(), any());
        }
    }

    /**
     * A stalled or slow-trickling extraction request (a per-read provider
     * timeout never fires while bytes still arrive) must be cancelled by an
     * independent total deadline, must never write a candidate after that
     * deadline, and must release the single learning worker so the next
     * queued extraction can run.
     */
    @Test
    void aStalledExtractionIsCancelledAtTheDeadlineAndTheWorkerServesTheNextOne() throws Exception {
        MemoryService memories = mock(MemoryService.class);
        // Counted down by the generation's close(): the deadline must cancel it.
        CountDownLatch watchdogCancelled = new CountDownLatch(1);
        CountDownLatch nextLearned = new CountDownLatch(1);
        AtomicInteger opens = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            if (((long) invocation.getArgument(2)) == 7L) nextLearned.countDown();
            return null;
        }).when(memories).learn(any(), any(), anyLong(), any(), any(), anyDouble(), org.mockito.ArgumentMatchers.any());
        ChatProvider provider = new ChatProvider() {
            @Override public String id() { return "gemini"; }
            @Override public Generation open(ChatInput input, String key) {
                if (opens.incrementAndGet() == 1) {
                    return new Generation() {
                        @Override public void stream(Consumer<String> delta) {
                            // Stalls the learning worker like a slow-drip provider.
                            try {
                                if (!watchdogCancelled.await(10, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("Extraction was not cancelled");
                                }
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new ProviderFailure(ProviderFailure.Code.CANCELLED, "stopped");
                            }
                        }
                        @Override public void close() {
                            watchdogCancelled.countDown();
                        }
                    };
                }
                return new Generation() {
                    @Override public void stream(Consumer<String> delta) {
                        delta.accept("{\"memories\":[{\"content\":\"I usually drink flat whites with oat\","
                                + "\"kind\":\"inference\",\"confidence\":0.8,"
                                + "\"evidence\":\"usually drink flat whites\"}]}");
                    }
                    @Override public void close() {}
                };
            }
        };
        ScheduledExecutorService timer = newTimer();
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            var service = new MemoryLearningService(memories, new ObjectMapper(), worker, timer,
                    Duration.ofSeconds(2));
            service.schedule("owner", UUID.randomUUID(), 5L, "My usual groceries are oats and milk",
                    provider, "gemini-3.5-flash-lite", "fake-key");
            assertThat(watchdogCancelled.await(5, TimeUnit.SECONDS))
                    .as("the independent deadline must close() the stalled generation")
                    .isTrue();
            // The worker must be free again: the next queued extraction must run.
            service.schedule("owner", UUID.randomUUID(), 7L, "I usually drink flat whites with oat milk",
                    provider, "gemini-3.5-flash-lite", "fake-key");
            assertThat(nextLearned.await(10, TimeUnit.SECONDS))
                    .as("the single learning worker must serve the next extraction")
                    .isTrue();
            verify(memories, org.mockito.Mockito.never()).learn(eq("owner"), any(), eq(5L),
                    any(), any(), anyDouble(), org.mockito.ArgumentMatchers.any());
        } finally {
            timer.shutdownNow();
        }
    }

    /** Even when a provider ignores close(), a stream ending after the deadline writes nothing. */
    @Test
    void aStreamThatFinishesAfterTheDeadlineNeverWritesACandidate() throws Exception {
        MemoryService memories = mock(MemoryService.class);
        CountDownLatch cancelled = new CountDownLatch(1);
        ChatProvider provider = new ChatProvider() {
            @Override public String id() { return "gemini"; }
            @Override public Generation open(ChatInput input, String key) {
                return new Generation() {
                    @Override public void stream(Consumer<String> delta) {
                        try {
                            if (!cancelled.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Extraction was not cancelled");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        delta.accept("{\"memories\":[{\"content\":\"I usually drink flat whites with oat\","
                                + "\"kind\":\"inference\",\"confidence\":0.8,"
                                + "\"evidence\":\"usually drink flat whites\"}]}");
                    }
                    @Override public void close() { cancelled.countDown(); }
                };
            }
        };
        ScheduledExecutorService timer = newTimer();
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            var service = new MemoryLearningService(memories, new ObjectMapper(), worker, timer,
                    Duration.ofMillis(20));
            service.schedule("owner", UUID.randomUUID(), 5L, "I usually drink flat whites with oat milk",
                    provider, "gemini-3.5-flash-lite", "fake-key");
            worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
            verify(memories, org.mockito.Mockito.never()).learn(any(), any(), anyLong(),
                    any(), any(), anyDouble(), org.mockito.ArgumentMatchers.any());
        } finally {
            timer.shutdownNow();
        }
    }

    private static ScheduledExecutorService newTimer() {
        return Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).name("test-learning-timer-").factory());
    }
}
