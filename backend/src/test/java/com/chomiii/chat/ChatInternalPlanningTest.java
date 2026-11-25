package com.chomiii.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chomiii.memory.MemoryLearningService;
import com.chomiii.memory.MemoryService;
import com.chomiii.planning.Plan;
import com.chomiii.planning.PlanningTurn;
import com.chomiii.planning.execution.PlanExecution;
import com.chomiii.planning.execution.PlanRunner;
import com.chomiii.provider.ChatProvider;
import com.chomiii.provider.ProviderCatalog;
import com.chomiii.provider.ProviderFailure;
import com.chomiii.tools.InternalTool;
import com.chomiii.tools.ToolDefinition;
import com.chomiii.tools.ToolExecutionContext;
import com.chomiii.tools.ToolRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ChatInternalPlanningTest {
    private static final Map<String, Object> PLAN = Map.of("steps", List.of(
            Map.of("create", Map.of("title", "Milk")), Map.of("create", Map.of("title", "Bread"))));

    @Test
    void ordinarySendRunsAModelSelectedPlanOnlyAfterTheTurnCommits() throws Exception {
        try (var h = new Harness((tools, delta) -> {
            assertThat(tools.invoke("plan.stage", PLAN)).doesNotContainKey("error");
            delta.accept("I already created both tasks. Here is my private plan: ...");
        })) {
            h.send();
            h.await();
            assertThat(h.opens).hasValue(1);
            assertThat(h.proposal).isNotNull();
            assertThat(h.proposal.executed()).isFalse();
            assertThat(h.content).isEqualTo("I prepared the to-do for your approval.");
            assertThat(h.status).isEqualTo("complete");
            verify(h.runner).run(eq("owner"), eq(h.conversation), eq(h.proposal.id()), any());
        }
    }

    @Test
    void clarificationIsAChatQuestionBeforeAnyStepsCanStart() throws Exception {
        Map<String, Object> draft = Map.of("steps", List.of(Map.of("create", Map.of("title", "Milk"))),
                "clarification", "Which day?");
        try (var h = new Harness((tools, delta) -> {
            tools.invoke("plan.stage", draft);
            delta.accept("Prepared.");
        })) {
            h.send(); h.await();
            assertThat(h.content).isEqualTo("Which day?");
            assertThat(h.proposal).isNull();
            verify(h.runner, never()).run(anyString(), any(), any(), any());
        }
    }

    @Test
    void plainAdviceUsesOneGenerationWithoutSavingOrRunningAPlan() throws Exception {
        try (var h = new Harness((tools, delta) -> delta.accept("Start with a short checklist and leave time for a break."))) {
            h.send();
            h.await();
            assertThat(h.content).isEqualTo("Start with a short checklist and leave time for a break.");
            assertThat(h.opens).hasValue(1);
            assertThat(h.proposal).isNull();
            verify(h.runner, never()).run(anyString(), any(), any(), any());
        }
    }

    @Test
    void badToolArgumentsRecoverWithoutBlankOutputOrFalseClaims() throws Exception {
        try (var h = new Harness((tools, delta) -> {
            assertThat(tools.invoke("plan.stage", Map.of("steps", List.of(Map.of("create", Map.of("title", "Milk", "approved", true)))))).containsEntry("errorCode", "INVALID_ARGUMENTS");
            delta.accept("All done, I created them!");
        })) {
            h.send();
            h.await();
            assertThat(h.status).isEqualTo("complete");
            assertThat(h.content).isEqualTo(PlanningTurn.FALLBACK);
            assertThat(h.proposal).isNull();
            assertThat(h.opens).hasValue(1);
        }
    }

    @Test
    void anEmptyProviderFallbackAfterBadArgumentsStillProducesASafeReply() throws Exception {
        try (var h = new Harness((tools, delta) -> {
            tools.invoke("plan.stage", Map.of("steps", List.of()));
            throw new ProviderFailure(ProviderFailure.Code.EMPTY_RESPONSE, "No text");
        })) {
            h.send();
            h.await();
            assertThat(h.status).isEqualTo("complete");
            assertThat(h.content).isEqualTo(PlanningTurn.FALLBACK);
        }
    }

    @Test
    void providerFailureAfterStagingDiscardsThePlan() throws Exception {
        try (var h = new Harness((tools, delta) -> {
            tools.invoke("plan.stage", PLAN);
            throw new ProviderFailure(ProviderFailure.Code.UNAVAILABLE, "Unavailable");
        })) {
            h.send();
            h.await();
            assertThat(h.status).isEqualTo("failed");
            assertThat(h.proposal).isNull();
            verify(h.runner, never()).run(anyString(), any(), any(), any());
        }
    }

    @Test
    void persistenceFailureNeverStartsTheRunner() throws Exception {
        try (var h = new Harness((tools, delta) -> {
            tools.invoke("plan.stage", PLAN);
            delta.accept("Please review.");
        })) {
            doThrow(new IllegalStateException("storage unavailable")).when(h.store)
                    .finishWithPlan(any(), anyString(), any(), any(), anyString(), any());
            h.send();
            h.await();
            assertThat(h.status).isEqualTo("failed");
            verify(h.runner, never()).run(anyString(), any(), any(), any());
        }
    }

    @Test
    void aRunnerFailureDoesNotEraseTheCommittedRequestOrInventCompletion() throws Exception {
        try (var h = new Harness((tools, delta) -> {
            tools.invoke("plan.stage", PLAN);
            delta.accept("Done!");
        })) {
            when(h.runner.run(anyString(), any(), any(), any())).thenThrow(new IllegalStateException("runner unavailable"));
            h.send();
            h.await();
            assertThat(h.status).isEqualTo("complete");
            assertThat(h.proposal).isNotNull();
            assertThat(h.content).contains("saved the request", "couldn't confirm").doesNotContain("Done!");
        }
    }

    @Test
    void cancellingAfterStagingDiscardsThePlanBeforePersistence() throws Exception {
        var staged = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var h = new Harness((tools, delta) -> {
            tools.invoke("plan.stage", PLAN);
            staged.countDown();
            try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
        })) {
            h.send();
            try {
                assertThat(staged.await(10, TimeUnit.SECONDS)).isTrue();
                h.chat.cancel(h.conversation, "owner", h.request);
            } finally { release.countDown(); }
            h.await();
            assertThat(h.status).isEqualTo("interrupted");
            assertThat(h.proposal).isNull();
            verify(h.runner, never()).run(anyString(), any(), any(), any());
        }
    }

    private static final class Harness implements AutoCloseable {
        final UUID conversation = UUID.randomUUID();
        final UUID request = UUID.randomUUID();
        final ChatTurnStore store = mock(ChatTurnStore.class);
        final PlanRunner runner = mock(PlanRunner.class);
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final AtomicInteger opens = new AtomicInteger();
        final ChatStreamService chat;
        volatile Plan proposal;
        volatile String content;
        volatile String status;

        Harness(BiConsumer<ChatProvider.Tools, Consumer<String>> behavior) {
            when(store.begin(any(), anyString(), any(), anyString())).thenReturn(new ChatTurnStore.Pending(UUID.randomUUID(), List.of()));
            doAnswer(call -> {
                content = call.getArgument(4);
                status = call.getArgument(5);
                return null;
            }).when(store).finish(any(), anyString(), any(), any(), anyString(), anyString());
            doAnswer(call -> {
                content = call.getArgument(4);
                proposal = call.getArgument(5);
                status = "complete";
                return null;
            }).when(store).finishWithPlan(any(), anyString(), any(), any(), anyString(), any());
            doAnswer(call -> { content = call.getArgument(4); return null; }).when(store)
                    .updatePlanReply(any(), anyString(), any(), any(), anyString());
            when(runner.run(eq("owner"), eq(conversation), any(), any())).thenAnswer(call -> {
                assertThat(status).as("The turn commits before execution").isEqualTo("complete");
                assertThat(proposal.id()).isEqualTo(call.getArgument(2));
                assertThat(((BooleanSupplier) call.getArgument(3)).getAsBoolean()).isFalse();
                return new PlanExecution("owner", conversation, proposal, PlanExecution.State.AWAITING_APPROVAL, 1,
                        Instant.EPOCH, Instant.EPOCH, proposal.steps().stream().map(step -> new PlanExecution.Step(
                                step.id(), UUID.randomUUID(), step.capability(), step.equals(proposal.steps().getFirst())
                                ? PlanExecution.State.AWAITING_APPROVAL : PlanExecution.State.PROPOSED,
                                null, null, step.equals(proposal.steps().getFirst()) ? UUID.randomUUID() : null, null)).toList());
            });
            var conversations = mock(ConversationRepository.class);
            when(conversations.find(conversation, "owner")).thenReturn(Optional.of(new ConversationDto.Summary(
                    conversation, "Chat", Instant.EPOCH, Instant.EPOCH)));
            ChatProvider provider = new ChatProvider() {
                @Override public String id() { return "gemini"; }
                @Override public Generation open(ChatInput input, String key) { throw new AssertionError("Tools expected"); }
                @Override public Generation open(ChatInput input, String key, Tools tools) {
                    opens.incrementAndGet();
                    return new Generation() {
                        @Override public void stream(Consumer<String> delta) { behavior.accept(tools, delta); }
                        @Override public void close() { tools.close(); }
                    };
                }
            };
            InternalTool create = new InternalTool() {
                @Override public ToolDefinition definition() { return new ToolDefinition("todo.create", "Create task",
                        Map.of("type", "object"), "todo.write", ToolDefinition.Effect.WRITE); }
                @Override public Result execute(ToolExecutionContext context, Map<String, Object> arguments) {
                    throw new AssertionError("No write during generation");
                }
            };
            chat = new ChatStreamService(store, conversations, mock(MemoryService.class), mock(MemoryLearningService.class),
                    new ProviderCatalog(List.of("model")), List.of(provider), executor, false, null, null,
                    new ToolRegistry(List.of(create)), null, null, runner);
        }

        void send() { chat.start(conversation, "owner", new ChatStreamController.MessageRequest(
                request, "gemini", "model", "Help me organize my tasks"), "test-key", false); }

        void await() throws InterruptedException {
            executor.shutdown();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }

        @Override public void close() { executor.close(); }
    }
}
