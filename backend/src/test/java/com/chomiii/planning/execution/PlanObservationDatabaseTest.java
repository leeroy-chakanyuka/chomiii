package com.chomiii.planning.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.chomiii.approval.ApprovalFacade;
import com.chomiii.planning.PlanParser;
import com.chomiii.todo.TodoService;
import com.chomiii.todo.TodoListTool;
import com.chomiii.tools.trace.ToolTraceService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "CHOMIII_DB_URL", matches = "jdbc:(postgresql|mysql):.*")
@SpringBootTest
@ActiveProfiles("local")
class PlanObservationDatabaseTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TodoService todos;
    @Autowired PlanExecutionService plans;
    @Autowired PlanRunner runner;
    @Autowired ApprovalFacade approvals;
    @Autowired ToolTraceService traces;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean TodoListTool listTool;
    String owner;
    UUID conversation;

    @AfterEach void cleanup() {
        if (owner != null) jdbc.update("delete from browser_sessions where owner_hash = ?", owner);
    }

    void setup() {
        owner = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        conversation = UUID.randomUUID();
        var now = Timestamp.from(Instant.now());
        jdbc.update("insert into browser_sessions (owner_hash, created_at) values (?, ?)", owner, now);
        jdbc.update("insert into conversations (id, owner_hash, title, created_at, updated_at) values (?, ?, ?, ?, ?)",
                conversation.toString(), owner, "Chat", now, now);
    }

    UUID add(String title) {
        return todos.create(owner, UUID.randomUUID(), new TodoService.CreateTodo(title, null, null, null, null, null)).id();
    }

    PlanExecution run(String title) {
        var plan = new PlanParser().parse("""
                {"goal":"Organize","summary":"List and complete","memoryReferences":[],"constraints":[],"missingInformation":[],"steps":[
                {"id":"list","title":"List","capability":"todo.list","dependsOn":[],"arguments":{}},
                {"id":"done","title":"Complete","capability":"todo.complete","dependsOn":["list"],"arguments":{"listStepId":"list","title":"%s"}}]}
                """.formatted(title), Set.of());
        plans.create(owner, conversation, plan);
        return runner.run(owner, conversation, plan.id());
    }

    @Test void resolvesSavedObservationButRequiresApprovalAndSurvivesReopen() {
        setup();
        UUID target = add("Milk");
        UUID other = add("Bread");
        var waiting = run("Milk");
        assertThat(waiting.steps().get(1).state()).isEqualTo(PlanExecution.State.AWAITING_APPROVAL);
        assertThat(todos.list(owner, null, 100)).allMatch(t -> t.status().equals("open"));
        var reopened = plans.find(owner, waiting.proposal().id());
        assertThat(reopened.steps().get(0).observation().todos()).extracting(PlanExecution.Todo::id)
                .containsExactlyInAnyOrder(target.toString(), other.toString());
        assertThatThrownBy(() -> plans.find("another-owner", waiting.proposal().id())).isInstanceOf(IllegalArgumentException.class);
        approvals.approve(owner, conversation, waiting.steps().get(1).pendingApprovalId());
        assertThat(todos.list(owner, null, 100)).anyMatch(t -> t.id().equals(target) && t.status().equals("completed"))
                .anyMatch(t -> t.id().equals(other) && t.status().equals("open"));
    }

    @Test void missingAndAmbiguousMatchesBlockWithoutApproval() {
        setup();
        add("Milk");
        var missing = run("Bread");
        assertThat(missing.steps().get(1).state()).isEqualTo(PlanExecution.State.BLOCKED);
        assertThat(missing.steps().get(1).pendingApprovalId()).isNull();
        add("Milk");
        var ambiguous = run("Milk");
        assertThat(ambiguous.steps().get(1).state()).isEqualTo(PlanExecution.State.BLOCKED);
        assertThat(ambiguous.steps().get(1).pendingApprovalId()).isNull();
    }

    @Test void cancellingAnAutomaticSegmentRollsBackItsObservationAndStopsBeforeApproval() {
        setup();
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> {
            var result = call.callRealMethod();
            cancelled.set(true);
            return result;
        }).when(listTool).execute(any(), any());
        var proposal = new PlanParser().parse("""
                {"goal":"Check tasks","summary":"Check then add","memoryReferences":[],"constraints":[],"missingInformation":[],"steps":[
                 {"id":"read","title":"Read tasks","capability":"todo.list","dependsOn":[],"arguments":{}},
                 {"id":"write","title":"Add milk","capability":"todo.create","dependsOn":["read"],"arguments":{"title":"Milk"}}]}
                """, Set.of());
        plans.create(owner, conversation, proposal);
        var stopped = runner.run(owner, conversation, proposal.id(), cancelled::get);
        assertThat(stopped.state()).isEqualTo(PlanExecution.State.CANCELLED);
        assertThat(stopped.steps()).allSatisfy(step -> {
            assertThat(step.state()).isEqualTo(PlanExecution.State.CANCELLED);
            assertThat(step.observation()).isNull();
            assertThat(step.pendingApprovalId()).isNull();
        });
        assertThat(todos.list(owner, null, 100)).isEmpty();
        assertThat(approvals.list(owner, conversation)).isEmpty();
        assertThat(traces.list(conversation, owner)).isEmpty();
        assertThat(runner.cancel(owner, conversation, proposal.id()).state()).isEqualTo(PlanExecution.State.CANCELLED);
    }

    @Test void exactLimitIsCompleteAndCanResolve() {
        setup();
        add("Milk");
        for (int i = 0; i < 9; i++) add("Task " + i);
        var result = run("Milk");
        assertThat(result.steps().get(0).observation().truncated()).isFalse();
        assertThat(result.steps().get(1).state()).isEqualTo(PlanExecution.State.AWAITING_APPROVAL);
    }

    @Test void listBeyondLimitIsTruncatedAndCannotResolve() {
        setup();
        add("Milk");
        for (int i = 0; i < 10; i++) add("Task " + i);
        var result = run("Milk");
        assertThat(result.steps().get(0).observation().truncated()).isTrue();
        assertThat(result.steps().get(1).state()).isEqualTo(PlanExecution.State.BLOCKED);
        assertThat(result.steps().get(1).pendingApprovalId()).isNull();
    }

    @Test void toolFailurePersistsAndBlocksDependentsWithoutStoppingIndependentWork() {
        setup();
        UUID target = add("Milk");
        var plan = failurePlan();
        var calls = failSecondList(new IllegalStateException("list unavailable"));

        var waiting = runner.run(owner, conversation, plan.id());
        var reopened = plans.find(owner, plan.id());
        assertThat(reopened).isEqualTo(waiting);
        assertThat(step(reopened, "first").state()).isEqualTo(PlanExecution.State.COMPLETED);
        assertThat(step(reopened, "first").observation().todos()).extracting(PlanExecution.Todo::id)
                .containsExactly(target.toString());
        assertThat(step(reopened, "failed").state()).isEqualTo(PlanExecution.State.FAILED);
        assertThat(step(reopened, "failed").observation()).isNull();
        assertThat(step(reopened, "dependent").state()).isEqualTo(PlanExecution.State.BLOCKED);
        assertThat(step(reopened, "independent").state()).isEqualTo(PlanExecution.State.COMPLETED);
        assertThat(step(reopened, "write").state()).isEqualTo(PlanExecution.State.AWAITING_APPROVAL);
        assertThat(calls.get()).isEqualTo(3);
        assertThat(traces.list(conversation, owner).stream()
                .filter(t -> t.stepId().equals(step(reopened, "failed").executionId()))
                .map(t -> t.status()).toList()).containsExactly("failed");

        UUID approval = step(reopened, "write").pendingApprovalId();
        assertThat(runner.run(owner, conversation, plan.id()).steps()).isEqualTo(reopened.steps());
        approvals.approve(owner, conversation, approval);
        runner.run(owner, conversation, plan.id());
        assertThat(calls.get()).isEqualTo(3);
        assertThat(todos.list(owner, null, 100)).filteredOn(t -> t.title().equals("Bread")).hasSize(1);
        var finished = plans.find(owner, plan.id());
        assertThat(step(finished, "failed").state()).isEqualTo(PlanExecution.State.FAILED);
        assertThat(step(finished, "write").state()).isEqualTo(PlanExecution.State.COMPLETED);
    }

    @Test void infrastructureFailureRetainsCommittedProgressAndCanRetryAttemptedWork() {
        setup();
        UUID target = add("Milk");
        var plan = failurePlan();
        var calls = failSecondList(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> runner.run(owner, conversation, plan.id()))
                .isInstanceOf(DataAccessResourceFailureException.class);
        var reopened = plans.find(owner, plan.id());
        assertThat(step(reopened, "first").state()).isEqualTo(PlanExecution.State.COMPLETED);
        assertThat(step(reopened, "first").observation().todos()).extracting(PlanExecution.Todo::id)
                .containsExactly(target.toString());
        assertThat(step(reopened, "failed").state()).isEqualTo(PlanExecution.State.PROPOSED);
        assertThat(step(reopened, "dependent").state()).isEqualTo(PlanExecution.State.PROPOSED);

        var waiting = runner.run(owner, conversation, plan.id());
        assertThat(step(waiting, "failed").state()).isEqualTo(PlanExecution.State.COMPLETED);
        assertThat(step(waiting, "dependent").state()).isEqualTo(PlanExecution.State.COMPLETED);
        assertThat(step(waiting, "independent").state()).isEqualTo(PlanExecution.State.COMPLETED);
        assertThat(step(waiting, "write").state()).isEqualTo(PlanExecution.State.AWAITING_APPROVAL);
        assertThat(calls.get()).isEqualTo(5);
        assertThat(traces.list(conversation, owner).stream()
                .filter(t -> t.stepId().equals(step(waiting, "first").executionId()))
                .map(t -> t.status()).toList()).containsExactly("succeeded");
        approvals.approve(owner, conversation, step(waiting, "write").pendingApprovalId());
        runner.run(owner, conversation, plan.id());
        assertThat(calls.get()).isEqualTo(5);
        assertThat(todos.list(owner, null, 100)).filteredOn(t -> t.title().equals("Bread")).hasSize(1);
    }

    @Test void cancellationAfterReadRollbackWinsOverFailureRecording() {
        setup();
        var plan = failurePlan();
        var calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() != 2) return invocation.callRealMethod();
            // Commit cancellation precisely between the read's rollback and the runner's recovery transaction.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_ROLLED_BACK) return;
                    var cancel = new TransactionTemplate(transactions);
                    cancel.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    cancel.executeWithoutResult(tx -> plans.cancelForConversation(owner, conversation, plan.id()));
                }
            });
            throw new IllegalStateException("list unavailable");
        }).when(listTool).execute(any(), any());

        var result = runner.run(owner, conversation, plan.id());
        assertThat(result.state()).isEqualTo(PlanExecution.State.CANCELLED);
        assertThat(step(result, "first").state()).isEqualTo(PlanExecution.State.COMPLETED);
        assertThat(step(result, "failed").state()).isEqualTo(PlanExecution.State.CANCELLED);
        assertThat(approvals.list(owner, conversation)).isEmpty();
        assertThat(traces.list(conversation, owner)).extracting(t -> t.status()).containsExactly("succeeded");
        assertThat(calls.get()).isEqualTo(2);
    }

    private com.chomiii.planning.Plan failurePlan() {
        var plan = new PlanParser().parse("""
                {"goal":"Organize","summary":"Check lists and add a task","memoryReferences":[],"constraints":[],"missingInformation":[],"steps":[
                {"id":"first","title":"First list","capability":"todo.list","dependsOn":[],"arguments":{}},
                {"id":"failed","title":"Second list","capability":"todo.list","dependsOn":[],"arguments":{}},
                {"id":"dependent","title":"Dependent list","capability":"todo.list","dependsOn":["failed"],"arguments":{}},
                {"id":"independent","title":"Independent list","capability":"todo.list","dependsOn":[],"arguments":{}},
                {"id":"write","title":"Add bread","capability":"todo.create","dependsOn":["independent"],"arguments":{"title":"Bread"}}]}
                """, Set.of());
        plans.create(owner, conversation, plan);
        return plan;
    }

    private AtomicInteger failSecondList(RuntimeException failure) {
        var calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) throw failure;
            return invocation.callRealMethod();
        }).when(listTool).execute(any(), any());
        return calls;
    }

    private static PlanExecution.Step step(PlanExecution execution, String id) {
        return execution.steps().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }
}
