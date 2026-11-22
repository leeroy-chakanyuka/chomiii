package com.chomiii.todo;

import static org.assertj.core.api.Assertions.assertThat;

import com.chomiii.approval.ApprovalFacade;
import com.chomiii.planning.PlanParser;
import com.chomiii.planning.execution.PlanExecution;
import com.chomiii.planning.execution.PlanExecutionService;
import com.chomiii.planning.execution.PlanRunner;
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
class TodoPlanAdaptationDatabaseTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TodoService todos;
    @Autowired PlanExecutionService plans;
    @Autowired PlanRunner runner;
    @Autowired ApprovalFacade approvals;
    private String owner;

    @AfterEach
    void cleanup() {
        if (owner != null) jdbc.update("delete from browser_sessions where owner_hash = ?", owner);
    }

    @Test
    void invalidProposalBlocksDependentsAndContinuesToExistingTaskApproval() {
        owner = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        UUID conversation = UUID.randomUUID();
        var now = Timestamp.from(Instant.now());
        jdbc.update("insert into browser_sessions (owner_hash, created_at) values (?, ?)", owner, now);
        jdbc.update("insert into conversations (id, owner_hash, title, created_at, updated_at) values (?, ?, ?, ?, ?)",
                conversation.toString(), owner, "Chat", now, now);
        var existing = todos.create(owner, UUID.randomUUID(), new TodoService.CreateTodo("Milk", null, null, null, null, null));
        var plan = new PlanParser().parse("""
                {"goal":"Organize tasks","summary":"Complete and add","memoryReferences":[],"constraints":[],"missingInformation":[],"steps":[
                 {"id":"bad","title":"Complete missing","capability":"todo.complete","dependsOn":[],"arguments":{"id":"00000000-0000-0000-0000-000000000001"}},
                 {"id":"dependent","title":"List after","capability":"todo.list","dependsOn":["bad"],"arguments":{}},
                 {"id":"independent","title":"Add milk","capability":"todo.create","dependsOn":[],"arguments":{"title":"Milk"}}]}
                """, Set.of());
        plans.create(owner, conversation, plan);
        var waiting = runner.run(owner, conversation, plan.id());
        assertThat(waiting.steps()).extracting(PlanExecution.Step::state).containsExactly(
                PlanExecution.State.BLOCKED, PlanExecution.State.BLOCKED, PlanExecution.State.AWAITING_APPROVAL);
        UUID approval = waiting.steps().get(2).pendingApprovalId();
        var result = approvals.approve(owner, conversation, approval);
        assertThat(result.result()).contains("already exists", existing.id().toString());
        assertThat(approvals.approve(owner, conversation, approval).result()).isEqualTo(result.result());
        assertThat(todos.list(owner, null, 100)).hasSize(1);
        assertThat(jdbc.queryForObject("select result_ref from action_approvals where id = ?", String.class, approval.toString()))
                .isEqualTo(existing.id().toString());
    }

    @Test
    void oversizedCanonicalArgumentsAreBlockedWithoutStoppingIndependentApproval() {
        owner = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        UUID conversation = UUID.randomUUID();
        var now = Timestamp.from(Instant.now());
        jdbc.update("insert into browser_sessions (owner_hash, created_at) values (?, ?)", owner, now);
        jdbc.update("insert into conversations (id, owner_hash, title, created_at, updated_at) values (?, ?, ?, ?, ?)",
                conversation.toString(), owner, "Chat", now, now);

        for (String description : java.util.List.of("a".repeat(3990), "é".repeat(2000))) {
            var plan = new PlanParser().parse("""
                    {"goal":"Add tasks","summary":"Add tasks","memoryReferences":[],"constraints":[],"missingInformation":[],"steps":[
                     {"id":"oversized","title":"Oversized","capability":"todo.create","dependsOn":[],"arguments":{"title":"Large","description":"%s"}},
                     {"id":"independent","title":"Small","capability":"todo.create","dependsOn":[],"arguments":{"title":"Small %d"}}]}
                    """.formatted(description, description.length()), Set.of());
            plans.create(owner, conversation, plan);
            var result = runner.run(owner, conversation, plan.id());
            assertThat(result.steps()).extracting(PlanExecution.Step::state).containsExactly(
                    PlanExecution.State.BLOCKED, PlanExecution.State.AWAITING_APPROVAL);
            assertThat(result.steps().get(0).pendingApprovalId()).isNull();
            assertThat(result.steps().get(1).pendingApprovalId()).isNotNull();
            assertThat(jdbc.queryForObject("select count(*) from action_approvals where owner_hash = ? and plan_id = ?",
                    Integer.class, owner, plan.id().toString())).isEqualTo(1);
            var pending = approvals.approve(owner, conversation, result.steps().get(1).pendingApprovalId());
            assertThat(pending.status()).isEqualTo("completed");
        }
        assertThat(todos.list(owner, null, 100)).extracting(PersonalTodo::title).containsExactlyInAnyOrder("Small 3990", "Small 2000");
    }
}
