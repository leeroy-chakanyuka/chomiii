package com.chomiii.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chomiii.chat.ConversationRepository;
import com.chomiii.chat.ChatTurnStore;
import com.chomiii.tools.ToolDefinition;
import com.chomiii.tools.ToolExecutionContext;
import com.chomiii.tools.ToolRegistry;
import com.chomiii.tools.trace.ToolTraceService;
import com.chomiii.tools.trace.ToolTraceCommand;
import com.chomiii.todo.TodoService;
import com.chomiii.planning.PlanParser;
import com.chomiii.planning.execution.PlanExecutionService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@EnabledIfEnvironmentVariable(named = "CHOMIII_DB_URL", matches = "jdbc:(postgresql|mysql):.*")
@SpringBootTest
@ActiveProfiles("local")
class ApprovalDatabaseTest {
    @Autowired ApprovalService approvals;
    @Autowired ConversationRepository conversations;
    @Autowired JdbcTemplate jdbc;
    @Autowired TodoService todos;
    @Autowired PlanExecutionService plans;
    @Autowired ApprovalFacade facade;
    @Autowired ToolRegistry registry;
    @Autowired ToolTraceService traces;
    @Autowired ChatTurnStore turns;
    private String owner;
    private String other;
    private UUID conversation;
    private final ToolDefinition tool = new ToolDefinition("todo.create", "Create a task",
            Map.of("type", "object", "properties", Map.of("title", Map.of("type", "string")),
                    "required", List.of("title"), "additionalProperties", false), "todo.write", ToolDefinition.Effect.WRITE);
    private final Map<String, Object> args = Map.of("title", "Buy milk");

    @BeforeEach
    void setup() {
        owner = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        other = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("insert into browser_sessions (owner_hash, created_at) values (?, ?), (?, ?)", owner, Timestamp.from(Instant.now()), other, Timestamp.from(Instant.now()));
        conversation = UUID.randomUUID();
        conversations.create(conversation, owner, Instant.now());
    }

    @AfterEach
    void cleanup() { jdbc.update("delete from browser_sessions where owner_hash in (?, ?)", owner, other); }

    private ApprovalService.Approval create() {
        return approvals.create(owner, conversation, UUID.randomUUID(), tool, args, Instant.now().plusSeconds(60), null, null);
    }

    @Test
    void ownerAndConversationAreEnforcedAndArgumentsMustMatch() {
        var action = create();
        assertThat(approvals.list(owner, conversation)).containsExactly(action);
        assertThatThrownBy(() -> approvals.list(other, conversation)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> approvals.create(other, conversation, UUID.randomUUID(), tool, args, Instant.now().plusSeconds(60), null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> approvals.approveAndExecute(other, conversation, action.id(), tool, args, a -> UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> approvals.approveAndExecute(owner, conversation, action.id(), tool, Map.of("title", "Buy tea"), a -> UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        assertThat(approvals.list(owner, conversation).getFirst().status()).isEqualTo(ApprovalService.Status.PENDING);
    }

    @Test
    void expiredRejectedAndCancelledCannotExecute() {
        var expired = create();
        jdbc.update("update action_approvals set expires_at = ? where id = ?", Timestamp.from(Instant.now().minusSeconds(1)), expired.id().toString());
        var rejected = create();
        var cancelled = create();
        approvals.reject(owner, conversation, rejected.id());
        approvals.cancel(owner, conversation, cancelled.id());
        for (var action : List.of(expired, rejected, cancelled)) {
            assertThatThrownBy(() -> approvals.approveAndExecute(owner, conversation, action.id(), tool, args, a -> { throw new AssertionError("executed"); }))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void callbackWritesRollBackOnFailure() {
        var action = create();
        assertThatThrownBy(() -> approvals.approveAndExecute(owner, conversation, action.id(), tool, args, a -> {
            jdbc.update("update conversations set title = ? where id = ?", "Changed", conversation.toString());
            throw new IllegalStateException("failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(conversations.find(conversation, owner).orElseThrow().title()).isEqualTo("New chat");
        assertThat(approvals.list(owner, conversation).getFirst().status()).isEqualTo(ApprovalService.Status.PENDING);
    }

    @Test
    void callbackWritesRollBackIfConsumptionFails() {
        var action = create();
        assertThatThrownBy(() -> approvals.approveAndExecute(owner, conversation, action.id(), tool, args, a -> {
            jdbc.update("update conversations set title = ? where id = ?", "Changed", conversation.toString());
            jdbc.update("update action_approvals set status = 'CANCELLED' where id = ?", action.id().toString());
            return UUID.randomUUID();
        })).isInstanceOf(IllegalStateException.class);
        assertThat(conversations.find(conversation, owner).orElseThrow().title()).isEqualTo("New chat");
        assertThat(approvals.list(owner, conversation).getFirst().status()).isEqualTo(ApprovalService.Status.PENDING);
    }

    @Test
    void credentialNamedArgumentsAreRejected() {
        var unsafe = new ToolDefinition("unsafe", "Unsafe", Map.of("type", "object"), "todo.write", ToolDefinition.Effect.WRITE);
        assertThatThrownBy(() -> approvals.create(owner, conversation, UUID.randomUUID(), unsafe,
                Map.of("api_key", "not-a-real-key"), Instant.now().plusSeconds(60), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void permissiveSchemasStillEnforceDepthCollectionAndEncodedSize() {
        var permissive = new ToolDefinition("test", "Test", Map.of("type", "object"), "test.write", ToolDefinition.Effect.WRITE);
        Object nested = "value";
        for (int i = 0; i < 9; i++) nested = List.of(nested);
        var deep = Map.<String, Object>of("value", nested);
        var cycle = new java.util.ArrayList<Object>();
        cycle.add(cycle);
        for (var invalid : List.of(deep, Map.<String, Object>of("value", cycle),
                Map.<String, Object>of("value", java.util.Collections.nCopies(101, "x")),
                Map.<String, Object>of("value", "é".repeat(2000)),
                Map.<String, Object>of("value", new Object()))) {
            assertThatThrownBy(() -> approvals.create(owner, conversation, UUID.randomUUID(), permissive,
                    invalid, Instant.now().plusSeconds(60), null, null)).isInstanceOf(IllegalArgumentException.class);
        }
        var action = approvals.create(owner, conversation, UUID.randomUUID(), permissive,
                Map.of("z", List.of(Map.of("b", 2, "a", 1)), "a", "ok"), Instant.now().plusSeconds(60), null, null);
        assertThat(action.argumentsJson()).isEqualTo("{\"a\":\"ok\",\"z\":[{\"a\":1,\"b\":2}]}");
    }

    @Test
    void expiryIsCappedButConsumedApprovalCanBeReplayedAfterExpiry() {
        assertThatThrownBy(() -> approvals.create(owner, conversation, UUID.randomUUID(), tool, args,
                Instant.now().plus(ApprovalService.MAX_PENDING_LIFETIME).plusSeconds(1), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        var action = create();
        UUID result = UUID.randomUUID();
        assertThat(approvals.approveAndExecute(owner, conversation, action.id(), tool, args, a -> result)).isEqualTo(result);
        jdbc.update("update action_approvals set expires_at = ? where id = ?", Timestamp.from(Instant.now().minusSeconds(1)), action.id().toString());
        assertThat(approvals.approveAndExecute(owner, conversation, action.id(), tool, args, a -> { throw new AssertionError("executed twice"); })).isEqualTo(result);
    }

    @Test
    void approvalAndConcurrentTodoCreationUseCompatibleLocks() throws Exception {
        var action = create();
        var start = new CountDownLatch(1);
        var request = new TodoService.CreateTodo("Buy milk", null, null, null, conversation, null);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var approval = pool.submit(() -> {
                start.await();
                return approvals.approveAndExecute(owner, conversation, action.id(), tool, args,
                        a -> todos.create(owner, a.id(), request).id());
            });
            var direct = pool.submit(() -> { start.await(); return todos.create(owner, UUID.randomUUID(), request).id(); });
            start.countDown();
            assertThat(approval.get(10, TimeUnit.SECONDS)).isNotNull();
            assertThat(direct.get(10, TimeUnit.SECONDS)).isNotNull();
        }
    }

    @Test
    void concurrentApprovalsExecuteOnceAndReturnSameResult() throws Exception {
        var action = create();
        var calls = new AtomicInteger();
        var start = new CountDownLatch(1);
        UUID result = UUID.randomUUID();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return approvals.approveAndExecute(owner, conversation, action.id(), tool, args, a -> { calls.incrementAndGet(); return result; }); });
            var second = pool.submit(() -> { start.await(); return approvals.approveAndExecute(owner, conversation, action.id(), tool, args, a -> { calls.incrementAndGet(); return result; }); });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(result);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(result);
        }
        assertThat(calls.get()).isEqualTo(1);
        assertThat(approvals.list(owner, conversation).getFirst().resultRef()).isEqualTo(result);
    }

    @Test
    void proposalReplayKeepsOriginalStateAndChangedArgumentsNeedNewApproval() {
        UUID request = UUID.randomUUID();
        Instant expiry = Instant.now().plusSeconds(60);
        var action = approvals.create(owner, conversation, request, tool, args, expiry, null, null);
        assertThat(approvals.create(owner, conversation, request, tool, args, expiry.plusSeconds(1), null, null)).isEqualTo(action);
        var changed = approvals.create(owner, conversation, request, tool, Map.of("title", "Buy tea"), expiry, null, null);
        assertThat(changed.id()).isNotEqualTo(action.id());
        approvals.reject(owner, conversation, action.id());
        assertThat(approvals.create(owner, conversation, request, tool, args, expiry, null, null).status()).isEqualTo(ApprovalService.Status.REJECTED);
        approvals.cancel(owner, conversation, changed.id());
        assertThat(approvals.create(owner, conversation, request, tool, Map.of("title", "Buy tea"), expiry, null, null).status()).isEqualTo(ApprovalService.Status.CANCELLED);

        UUID consumedRequest = UUID.randomUUID();
        var consumed = approvals.create(owner, conversation, consumedRequest, tool, args, expiry, null, null);
        UUID result = UUID.randomUUID();
        approvals.approveAndExecute(owner, conversation, consumed.id(), tool, args, a -> result);
        var replay = approvals.create(owner, conversation, consumedRequest, tool, args, Instant.now().minusSeconds(1), null, null);
        assertThat(replay.id()).isEqualTo(consumed.id());
        assertThat(replay.status()).isEqualTo(ApprovalService.Status.CONSUMED);
        assertThat(replay.resultRef()).isEqualTo(result);

        UUID expiredRequest = UUID.randomUUID();
        var expired = approvals.create(owner, conversation, expiredRequest, tool, args, expiry, null, null);
        jdbc.update("update action_approvals set expires_at = ? where id = ?", Timestamp.from(Instant.now().minusSeconds(1)), expired.id().toString());
        var expiredReplay = approvals.create(owner, conversation, expiredRequest, tool, args, expiry, null, null);
        assertThat(expiredReplay.id()).isEqualTo(expired.id());
        assertThat(expiredReplay.expiresAt()).isBefore(Instant.now());
        assertThatThrownBy(() -> approvals.approveAndExecute(owner, conversation, expiredReplay.id(), tool, args, a -> result))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void concurrentProposalRetriesReturnOneApproval() throws Exception {
        UUID request = UUID.randomUUID();
        Instant expiry = Instant.now().plusSeconds(60);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return approvals.create(owner, conversation, request, tool, args, expiry, null, null); });
            var second = pool.submit(() -> { start.await(); return approvals.create(owner, conversation, request, tool, args, expiry, null, null); });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        }
        assertThat(approvals.list(owner, conversation)).hasSize(1);
    }

    @Test
    void planReferencesMustIdentifyAnOwnedMatchingExecutionStep() {
        var proposal = new PlanParser().parse("""
                {"goal":"Plan","summary":"Plan","memoryReferences":[],"constraints":[],"missingInformation":[],"steps":[
                {"id":"first","title":"Change event","capability":"calendar.update","dependsOn":[]},
                {"id":"second","title":"Change another event","capability":"calendar.update","dependsOn":[]},
                {"id":"third","title":"Prepare","capability":"user.prepare","dependsOn":[]}]}
                """, Set.of());
        var execution = plans.create(owner, conversation, proposal);
        UUID first = execution.steps().get(0).executionId();
        UUID second = execution.steps().get(1).executionId();
        UUID mismatch = execution.steps().get(2).executionId();
        var calendar = new ToolDefinition("calendar.update", "Update", tool.parameters(), "calendar.update", ToolDefinition.Effect.WRITE);
        UUID request = UUID.randomUUID();
        Instant expiry = Instant.now().plusSeconds(60);
        var action = approvals.create(owner, conversation, request, calendar, args, expiry, proposal.id(), first);
        assertThat(approvals.create(owner, conversation, request, calendar, args, expiry, proposal.id(), first).id()).isEqualTo(action.id());
        assertThat(approvals.create(owner, conversation, request, calendar, args, expiry, proposal.id(), second).id()).isNotEqualTo(action.id());
        assertThatThrownBy(() -> approvals.create(owner, conversation, request, calendar, args, expiry, proposal.id(), mismatch)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> approvals.create(owner, conversation, request, calendar, args, expiry, proposal.id(), UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> approvals.create(owner, conversation, request, calendar, args, expiry, UUID.randomUUID(), first)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> approvals.create(owner, conversation, request, calendar, args, expiry, proposal.id(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> approvals.create(owner, conversation, request, calendar, args, expiry, null, first)).isInstanceOf(IllegalArgumentException.class);
        UUID foreignConversation = UUID.randomUUID();
        conversations.create(foreignConversation, other, Instant.now());
        var foreign = plans.create(other, foreignConversation, new PlanParser().parse("""
                {"goal":"Plan","summary":"Plan","memoryReferences":[],"constraints":[],"missingInformation":[],"steps":[
                {"id":"first","title":"Change event","capability":"calendar.update","dependsOn":[]}]}
                """, Set.of()));
        assertThatThrownBy(() -> approvals.create(owner, conversation, request, calendar, args, expiry,
                foreign.proposal().id(), foreign.steps().get(0).executionId())).isInstanceOf(IllegalArgumentException.class);
        UUID otherConversation = UUID.randomUUID();
        conversations.create(otherConversation, owner, Instant.now());
        assertThatThrownBy(() -> approvals.create(owner, otherConversation, request, calendar, args, expiry, proposal.id(), first)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void plannedStepMatchesOperationNameNotAuthorizationCapability() {
        var proposal = new PlanParser().parse("""
                {"goal":"Remember","summary":"Remember","memoryReferences":[],"constraints":[],"missingInformation":[],"steps":[
                {"id":"first","title":"Create task","capability":"todo.create","dependsOn":[],"arguments":{"title":"Buy milk"}}]}
                """, Set.of());
        var execution = plans.create(owner, conversation, proposal);
        UUID step = execution.steps().getFirst().executionId();
        var definition = registry.definition("todo.create").orElseThrow();
        var action = approvals.create(owner, conversation, UUID.randomUUID(), definition, args,
                Instant.now().plusSeconds(60), proposal.id(), step);
        assertThat(action.toolName()).isEqualTo("todo.create");
        assertThat(action.capability()).isEqualTo("todo.write");
        var wrong = registry.definition("todo.complete").orElseThrow();
        assertThatThrownBy(() -> approvals.create(owner, conversation, UUID.randomUUID(), wrong,
                Map.of("id", UUID.randomUUID().toString()), Instant.now().plusSeconds(60), proposal.id(), step))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void explicitApprovalCreatesExactlyThePreviewedTodoAndUpdatesTrace() {
        UUID request = UUID.randomUUID();
        var pending = turns.begin(conversation, owner, request, "Help me remember");
        var definition = registry.definition("todo.create").orElseThrow();
        var context = new ToolExecutionContext(owner, conversation, request, "do-not-persist-this", false, ignored -> {});
        var input = Map.<String, Object>of("title", "  Buy milk  ", "description", "  Whole milk  ",
                "dueAt", "2027-02-02T12:30:00.123456789Z", "timezone", " UTC ");
        var trace = traces.recordRequested(owner, new ToolTraceCommand.Requested(conversation, request, null, null,
                definition.name(), definition.capability(), definition.effect(), "gemini", "test", null, null, input));
        var view = facade.propose(context, definition, input, trace.invocationId());
        assertThat(facade.list(owner, conversation)).isEmpty();
        assertThat(view.preview()).contains("Buy milk", "Whole milk", "2027-02-02T12:30:00.123456Z", "UTC").doesNotContain("do-not-persist-this");
        assertThat(todos.list(owner, null, 100)).isEmpty();
        assertThat(approvals.isAuthorized(owner, conversation, request, view.id(), definition, input)).isFalse();
        assertThatThrownBy(() -> facade.approve(owner, conversation, view.id())).isInstanceOf(IllegalStateException.class);
        turns.finish(conversation, owner, request, pending.assistantId(), "Review the action", "complete");
        assertThat(facade.list(owner, conversation)).extracting(ApprovalView::id).contains(view.id());
        var done = facade.approve(owner, conversation, view.id());
        assertThat(done.status()).isEqualTo("completed");
        assertThat(facade.approve(owner, conversation, view.id())).isEqualTo(done);
        assertThat(todos.list(owner, null, 100)).singleElement().satisfies(todo -> {
            assertThat(todo.title()).isEqualTo("Buy milk");
            assertThat(todo.description()).isEqualTo("Whole milk");
            assertThat(todo.dueAt()).isEqualTo(Instant.parse("2027-02-02T12:30:00.123456Z"));
        });
        assertThat(traces.list(conversation, owner)).singleElement().satisfies(t -> {
            assertThat(t.status()).isEqualTo("succeeded");
            assertThat(t.approvalId()).isEqualTo(view.id());
            assertThat(t.toString()).doesNotContain("Whole milk", "do-not-persist-this");
        });
    }

    @Test
    void facadeRejectsForeignTargetsAndRejectionNeverWrites() {
        UUID request = UUID.randomUUID();
        turns.begin(conversation, owner, request, "Help me remember");
        var context = new ToolExecutionContext(owner, conversation, request, null, false, ignored -> {});
        var complete = registry.definition("todo.complete").orElseThrow();
        var foreign = todos.create(other, UUID.randomUUID(), new TodoService.CreateTodo("Private task", null, null, null, null, null));
        assertThatThrownBy(() -> facade.propose(context, complete, Map.of("id", foreign.id().toString()), UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        var definition = registry.definition("todo.create").orElseThrow();
        var input = Map.<String, Object>of("title", "A task");
        var trace = traces.recordRequested(owner, new ToolTraceCommand.Requested(conversation, request, null, null,
                definition.name(), definition.capability(), definition.effect(), "gemini", "test", null, null, input));
        var proposal = facade.propose(context, definition, input, trace.invocationId());
        assertThatThrownBy(() -> facade.approve(other, conversation, proposal.id())).isInstanceOf(IllegalArgumentException.class);
        assertThat(facade.reject(owner, conversation, proposal.id()).status()).isEqualTo("rejected");
        assertThatThrownBy(() -> facade.approve(owner, conversation, proposal.id())).isInstanceOf(IllegalStateException.class);
        assertThat(todos.list(owner, null, 100)).isEmpty();
        assertThat(traces.list(conversation, owner)).singleElement().satisfies(t -> assertThat(t.status()).isEqualTo("denied"));
    }

    @Test
    void changedDefinitionCannotAuthorizeAnExistingProposal() {
        var action = create();
        var changed = new ToolDefinition(tool.name(), "Changed description", tool.parameters(), tool.capability(), tool.effect());
        assertThatThrownBy(() -> approvals.approveAndExecute(owner, conversation, action.id(), changed, args, a -> UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(approvals.isAuthorized(owner, conversation, action.requestId(), action.id(), tool, args)).isFalse();
    }

    @Test
    void authorizationExistsOnlyInsideExactApprovedCallback() {
        var action = create();
        UUID result = UUID.randomUUID();
        approvals.approveAndExecute(owner, conversation, action.id(), tool, args, a -> {
            assertThat(approvals.isAuthorized(owner, conversation, action.requestId(), action.id(), tool, args)).isTrue();
            assertThat(approvals.isAuthorized(other, conversation, action.requestId(), action.id(), tool, args)).isFalse();
            assertThat(approvals.isAuthorized(owner, conversation, UUID.randomUUID(), action.id(), tool, args)).isFalse();
            assertThat(approvals.isAuthorized(owner, conversation, action.requestId(), action.id(), tool, Map.of("title", "Changed"))).isFalse();
            return result;
        });
        assertThat(approvals.isAuthorized(owner, conversation, action.requestId(), action.id(), tool, args)).isFalse();
    }

    @Test
    void concurrentFacadeApprovalsCreateOnlyOneTodo() throws Exception {
        UUID request = UUID.randomUUID();
        var pending = turns.begin(conversation, owner, request, "Help me remember");
        var context = new ToolExecutionContext(owner, conversation, request, null, false, ignored -> {});
        var definition = registry.definition("todo.create").orElseThrow();
        var input = Map.<String, Object>of("title", "One task");
        var trace = traces.recordRequested(owner, new ToolTraceCommand.Requested(conversation, request, null, null,
                definition.name(), definition.capability(), definition.effect(), "gemini", "test", null, null, input));
        var proposal = facade.propose(context, definition, input, trace.invocationId());
        turns.finish(conversation, owner, request, pending.assistantId(), "Review the action", "complete");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return facade.approve(owner, conversation, proposal.id()); });
            var second = pool.submit(() -> { start.await(); return facade.approve(owner, conversation, proposal.id()); });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        }
        assertThat(todos.list(owner, null, 100)).hasSize(1);
    }

    @Test
    void failedBindingRollsBackAndCancellationPreventsLateProposals() {
        UUID request = UUID.randomUUID();
        turns.begin(conversation, owner, request, "Help me remember");
        var context = new ToolExecutionContext(owner, conversation, request, null, false, ignored -> {});
        var definition = registry.definition("todo.create").orElseThrow();
        var input = Map.<String, Object>of("title", "A task");
        assertThatThrownBy(() -> facade.propose(context, definition, input, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(facade.list(owner, conversation)).isEmpty();
        var trace = traces.recordRequested(owner, new ToolTraceCommand.Requested(conversation, request, null, null,
                definition.name(), definition.capability(), definition.effect(), "gemini", "test", null, null, input));
        var proposal = facade.propose(context, definition, input, trace.invocationId());
        facade.cancelRequest(owner, conversation, request);
        assertThatThrownBy(() -> facade.approve(owner, conversation, proposal.id())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> facade.propose(context, definition, input, UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        assertThat(todos.list(owner, null, 100)).isEmpty();
        assertThat(traces.list(conversation, owner)).singleElement().satisfies(t -> assertThat(t.status()).isEqualTo("cancelled"));
    }

    @Test
    void replayDoesNotLeaveASecondPendingTraceAndFailedTurnCannotExecute() {
        UUID request = UUID.randomUUID();
        turns.begin(conversation, owner, request, "Help me remember");
        var context = new ToolExecutionContext(owner, conversation, request, null, false, ignored -> {});
        var definition = registry.definition("todo.create").orElseThrow();
        var input = Map.<String, Object>of("title", "A task");
        var command = new ToolTraceCommand.Requested(conversation, request, null, null, definition.name(),
                definition.capability(), definition.effect(), "gemini", "test", null, null, input);
        var first = traces.recordRequested(owner, command);
        var proposal = facade.propose(context, definition, input, first.invocationId());
        var second = traces.recordRequested(owner, command);
        assertThat(facade.propose(context, definition, input, second.invocationId()).id()).isEqualTo(proposal.id());
        assertThat(traces.list(conversation, owner)).extracting(t -> t.status())
                .containsExactlyInAnyOrder("awaiting_approval", "cancelled");
        facade.failRequest(owner, conversation, request);
        assertThatThrownBy(() -> facade.approve(owner, conversation, proposal.id())).isInstanceOf(IllegalStateException.class);
        assertThat(todos.list(owner, null, 100)).isEmpty();
    }
}
