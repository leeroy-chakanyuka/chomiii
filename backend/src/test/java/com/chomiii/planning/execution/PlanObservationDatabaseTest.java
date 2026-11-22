package com.chomiii.planning.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chomiii.approval.ApprovalFacade;
import com.chomiii.planning.PlanParser;
import com.chomiii.todo.TodoService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@EnabledIfEnvironmentVariable(named = "CHOMIII_DB_URL", matches = "jdbc:(postgresql|mysql):.*")
@SpringBootTest
@ActiveProfiles("local")
class PlanObservationDatabaseTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TodoService todos;
    @Autowired PlanExecutionService plans;
    @Autowired PlanRunner runner;
    @Autowired ApprovalFacade approvals;
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
}
