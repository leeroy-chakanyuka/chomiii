package com.chomiii.planning.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chomiii.planning.Plan;
import com.chomiii.planning.PlanParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
class PlanExecutionDatabaseTest {
    @Autowired PlanExecutionService service;
    @Autowired PlanRunner runner;
    @Autowired com.chomiii.approval.ApprovalFacade approvals;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    String owner;
    String stranger;
    UUID conversation;
    Plan proposal;

    @AfterEach
    void cleanup() {
        jdbc.update("delete from browser_sessions where owner_hash in (?, ?)", owner, stranger);
    }

    @BeforeEach
    void setup() {
        owner = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        stranger = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        conversation = UUID.randomUUID();
        var now = Timestamp.from(Instant.now());
        jdbc.update("insert into browser_sessions (owner_hash, created_at) values (?, ?), (?, ?)", owner, now, stranger, now);
        jdbc.update("insert into conversations (id, owner_hash, title, created_at, updated_at) values (?, ?, ?, ?, ?)",
                conversation.toString(), owner, "Chat", now, now);
        proposal = new PlanParser().parse("""
                {"goal":"Plan a trip","summary":"Prepare together","memoryReferences":[],
                 "constraints":["Keep it affordable"],"missingInformation":[],"steps":[
                 {"id":"first","title":"Make a list","capability":"user.prepare","dependsOn":[]},
                 {"id":"second","title":"Choose","capability":"user.decide","dependsOn":["first"]}]}
                """, Set.of());
    }

    @Test
    void revisionRetainsCompletedWorkCancelsApprovalAndRequiresNewApprovalForChangedTask() {
        proposal = new PlanParser().parse("""
                {"goal":"Organize tasks","summary":"List and add","memoryReferences":[],"constraints":[],"missingInformation":[],"steps":[
                 {"id":"read","title":"List tasks","capability":"todo.list","dependsOn":[],"arguments":{}},
                 {"id":"write","title":"Add task","capability":"todo.create","dependsOn":[],"arguments":{"title":"Buy milk"}}]}
                """, Set.of());
        var revised = new PlanParser().parse("""
                {"goal":"Organize tasks","summary":"Add revised task","memoryReferences":[],"constraints":[],"missingInformation":[],"steps":[
                 {"id":"write","title":"Add task","capability":"todo.create","dependsOn":[],"arguments":{"title":"Buy oat milk"}}]}
                """, Set.of());
        service.create(owner, conversation, proposal);
        var waiting = runner.run(owner, conversation, proposal.id());
        var oldApproval = waiting.steps().get(1).pendingApprovalId();
        assertThatThrownBy(() -> service.revise(owner, conversation, proposal.id(), waiting.version() - 1, revised))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.list(owner, conversation)).hasSize(1);
        service.revise(owner, conversation, proposal.id(), waiting.version(), revised);
        var old = service.find(owner, proposal.id());
        assertThat(old.steps()).extracting(PlanExecution.Step::state).containsExactly(
                PlanExecution.State.COMPLETED, PlanExecution.State.CANCELLED);
        assertThat(jdbc.queryForObject("select status from action_approvals where id = ?", String.class, oldApproval.toString())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select revised_from_id from plan_executions where id = ?", String.class, revised.id().toString())).isEqualTo(proposal.id().toString());
        assertThat(service.find(owner, revised.id()).proposal().steps().get(0).arguments()).containsEntry("title", "Buy oat milk");
        var newWaiting = runner.run(owner, conversation, revised.id());
        assertThat(newWaiting.steps().get(0).pendingApprovalId()).isNotNull().isNotEqualTo(oldApproval);
        assertThat(jdbc.queryForObject("select count(*) from personal_todos where owner_hash = ?", Integer.class, owner)).isZero();
    }

    @Test
    void runPausesForEachWriteAndDoesNotRepeatCompletedSteps() {
        proposal = new PlanParser().parse("""
                {"goal":"Organize tasks","summary":"List and add tasks","memoryReferences":[],
                 "constraints":[],"missingInformation":[],"steps":[
                 {"id":"read","title":"List tasks","capability":"todo.list","dependsOn":[],"arguments":{}},
                 {"id":"write","title":"Add task","capability":"todo.create","dependsOn":[],"arguments":{"title":"Buy milk"}},
                 {"id":"after","title":"List again","capability":"todo.list","dependsOn":["write"],"arguments":{}}]}
                """, Set.of());
        service.create(owner, conversation, proposal);
        assertThatThrownBy(() -> runner.run(stranger, conversation, proposal.id())).isInstanceOf(IllegalArgumentException.class);
        var waiting = runner.run(owner, conversation, proposal.id());
        assertThat(waiting.steps()).extracting(PlanExecution.Step::state).containsExactly(
                PlanExecution.State.COMPLETED, PlanExecution.State.AWAITING_APPROVAL, PlanExecution.State.PROPOSED);
        assertThat(jdbc.queryForObject("select count(*) from personal_todos where owner_hash = ?", Integer.class, owner)).isZero();
        var approvalId = waiting.steps().get(1).pendingApprovalId();
        assertThat(runner.run(owner, conversation, proposal.id()).version()).isEqualTo(waiting.version());
        approvals.approve(owner, conversation, approvalId);
        var completed = runner.run(owner, conversation, proposal.id());
        assertThat(completed.state()).isEqualTo(PlanExecution.State.COMPLETED);
        assertThat(runner.run(owner, conversation, proposal.id()).version()).isEqualTo(completed.version());
        assertThat(jdbc.queryForObject("select count(*) from personal_todos where owner_hash = ?", Integer.class, owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from tool_traces where owner_hash = ? and plan_id = ? and status = 'succeeded'", Integer.class, owner, proposal.id().toString())).isEqualTo(3);
    }

    @Test
    void rejectedWriteBlocksDependentsButIndependentReadCanRun() {
        proposal = new PlanParser().parse("""
                {"goal":"Organize tasks","summary":"Add and list tasks","memoryReferences":[],
                 "constraints":[],"missingInformation":[],"steps":[
                 {"id":"write","title":"Add task","capability":"todo.create","dependsOn":[],"arguments":{"title":"Buy milk"}},
                 {"id":"dependent","title":"List after","capability":"todo.list","dependsOn":["write"],"arguments":{}},
                 {"id":"independent","title":"List tasks","capability":"todo.list","dependsOn":[],"arguments":{}}]}
                """, Set.of());
        service.create(owner, conversation, proposal);
        var waiting = runner.run(owner, conversation, proposal.id());
        approvals.reject(owner, conversation, waiting.steps().get(0).pendingApprovalId());
        var result = runner.run(owner, conversation, proposal.id());
        assertThat(result.steps()).extracting(PlanExecution.Step::state).containsExactly(
                PlanExecution.State.FAILED, PlanExecution.State.BLOCKED, PlanExecution.State.COMPLETED);
        assertThat(jdbc.queryForObject("select count(*) from personal_todos where owner_hash = ?", Integer.class, owner)).isZero();
    }

    @Test
    void expiredApprovalCannotBeRunOrApproved() {
        proposal = new PlanParser().parse("""
                {"goal":"Organize tasks","summary":"Add a task","memoryReferences":[],
                 "constraints":[],"missingInformation":[],"steps":[
                 {"id":"write","title":"Add task","capability":"todo.create","dependsOn":[],"arguments":{"title":"Buy milk"}}]}
                """, Set.of());
        service.create(owner, conversation, proposal);
        var waiting = runner.run(owner, conversation, proposal.id());
        var approvalId = waiting.steps().get(0).pendingApprovalId();
        jdbc.update("update action_approvals set expires_at = ? where id = ?", Timestamp.from(Instant.now().minusSeconds(1)), approvalId.toString());
        var expired = runner.run(owner, conversation, proposal.id());
        assertThat(expired.steps().get(0).state()).isEqualTo(PlanExecution.State.FAILED);
        assertThatThrownBy(() -> approvals.approve(owner, conversation, approvalId)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from personal_todos where owner_hash = ?", Integer.class, owner)).isZero();
    }

    @Test
    void scopesToOwnerAndReconstructsProposalAndProgress() {
        assertThatThrownBy(() -> service.create(stranger, conversation, proposal)).isInstanceOf(IllegalArgumentException.class);
        var created = service.create(owner, conversation, proposal);
        assertThatThrownBy(() -> service.find(stranger, proposal.id())).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.list(stranger, conversation)).isEmpty();
        var running = service.start(owner, proposal.id(), created.version(), "first", "args:123");
        var reopened = new PlanExecutionService(jdbc, json).find(owner, proposal.id());
        assertThat(reopened).isEqualTo(running);
        assertThat(reopened.proposal()).isEqualTo(proposal);
        assertThat(reopened.proposal().constraints()).containsExactly("Keep it affordable");
        assertThat(reopened.proposal().steps().get(1).dependsOn()).containsExactly("first");
        assertThat(service.list(owner, conversation)).containsExactly(reopened);
    }

    @Test
    void requiresVersionDependenciesAndBoundSuccessfulObservation() {
        var created = service.create(owner, conversation, proposal);
        assertThatThrownBy(() -> service.start(owner, proposal.id(), 0, "second", null)).isInstanceOf(IllegalStateException.class);
        var running = service.start(owner, proposal.id(), 0, "first", null);
        assertThatThrownBy(() -> service.fail(owner, proposal.id(), 0, "first")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.completeFromObservation(owner, proposal.id(), 1, "first", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        UUID trace = trace(running, "first", "failed");
        assertThatThrownBy(() -> service.completeFromObservation(owner, proposal.id(), 1, "first", trace))
                .isInstanceOf(IllegalArgumentException.class);
        UUID malformed = trace(running, "first", "succeeded");
        jdbc.update("update tool_traces set authorization_decision = null, started_at = null, finished_at = null, duration_ms = null where id = ?", malformed.toString());
        assertThatThrownBy(() -> service.completeFromObservation(owner, proposal.id(), 1, "first", malformed))
                .isInstanceOf(IllegalArgumentException.class);
        jdbc.update("update tool_traces set authorization_decision = 'ALLOW' where id = ?", malformed.toString());
        assertThatThrownBy(() -> service.completeFromObservation(owner, proposal.id(), 1, "first", malformed))
                .isInstanceOf(IllegalArgumentException.class);
        jdbc.update("update tool_traces set started_at = ? where id = ?", Timestamp.from(Instant.now()), malformed.toString());
        assertThatThrownBy(() -> service.completeFromObservation(owner, proposal.id(), 1, "first", malformed))
                .isInstanceOf(IllegalArgumentException.class);
        jdbc.update("update tool_traces set authorization_decision = 'DENY', finished_at = ?, duration_ms = 0 where id = ?",
                Timestamp.from(Instant.now()), malformed.toString());
        assertThatThrownBy(() -> service.completeFromObservation(owner, proposal.id(), 1, "first", malformed))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.find(owner, proposal.id()).steps().get(0).state()).isEqualTo(PlanExecution.State.RUNNING);
        UUID success = trace(running, "first", "succeeded");
        var firstDone = service.completeFromObservation(owner, proposal.id(), 1, "first", success);
        assertThat(firstDone.steps().get(0).observationRef()).isEqualTo(success);
        var second = service.start(owner, proposal.id(), 2, "second", null);
        var done = service.completeFromObservation(owner, proposal.id(), 3, "second", trace(second, "second", "succeeded"));
        assertThat(done.state()).isEqualTo(PlanExecution.State.COMPLETED);
        assertThatThrownBy(() -> service.cancel(owner, proposal.id(), done.version())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancellationAndFailureCannotInventSuccess() {
        service.create(owner, conversation, proposal);
        var running = service.start(owner, proposal.id(), 0, "first", null);
        assertThatThrownBy(() -> service.cancel(owner, proposal.id(), running.version())).isInstanceOf(IllegalStateException.class);
        var failed = service.fail(owner, proposal.id(), running.version(), "first");
        assertThat(failed.state()).isEqualTo(PlanExecution.State.FAILED);
        assertThatThrownBy(() -> service.start(owner, proposal.id(), failed.version(), "second", null)).isInstanceOf(IllegalStateException.class);
        var cancelled = service.cancel(owner, proposal.id(), failed.version());
        assertThat(cancelled.state()).isEqualTo(PlanExecution.State.CANCELLED);
        assertThat(cancelled.steps().get(0).state()).isEqualTo(PlanExecution.State.FAILED);
        assertThat(cancelled.steps().get(1).state()).isEqualTo(PlanExecution.State.CANCELLED);
    }

    @Test
    void failureDoesNotStopIndependentStepsOrApprovalsButStillBlocksDependents() {
        proposal = new PlanParser().parse("""
                {"goal":"Arrange a trip","summary":"Prepare together","memoryReferences":[],
                 "constraints":[],"missingInformation":[],"steps":[
                 {"id":"first","title":"Make a list","capability":"user.prepare","dependsOn":[]},
                 {"id":"independent","title":"Choose","capability":"user.decide","dependsOn":[]},
                 {"id":"dependent","title":"Review list","capability":"user.prepare","dependsOn":["first"]},
                 {"id":"approval","title":"Update event","capability":"calendar.update","dependsOn":[]},
                 {"id":"dependentApproval","title":"Send update","capability":"gmail.send","dependsOn":["first"]}]}
                """, Set.of());
        var created = service.create(owner, conversation, proposal);
        // All approval-required catalog capabilities are currently disconnected. Simulate a future trusted unblock.
        jdbc.update("update plan_execution_steps set state = 'PROPOSED' where plan_id = ? and step_id in ('approval', 'dependentApproval')", proposal.id().toString());
        var running = service.start(owner, proposal.id(), created.version(), "first", null);
        var failed = service.fail(owner, proposal.id(), running.version(), "first");
        assertThat(failed.state()).isEqualTo(PlanExecution.State.FAILED);
        assertThatThrownBy(() -> service.start(owner, proposal.id(), failed.version(), "dependent", null))
                .isInstanceOf(IllegalStateException.class);
        var dependentApproval = failed.steps().stream().filter(s -> s.id().equals("dependentApproval")).findFirst().orElseThrow();
        UUID blockedApproval = approval(owner, conversation, proposal.id(), dependentApproval.executionId(), dependentApproval.capability(), "PENDING", Instant.now().plusSeconds(600));
        assertThatThrownBy(() -> service.awaitApproval(owner, proposal.id(), failed.version(), "dependentApproval", blockedApproval))
                .isInstanceOf(IllegalStateException.class);
        var independent = service.start(owner, proposal.id(), failed.version(), "independent", null);
        assertThat(independent.state()).isEqualTo(PlanExecution.State.RUNNING);
        var done = service.completeFromObservation(owner, proposal.id(), independent.version(), "independent", trace(independent, "independent", "succeeded"));
        assertThat(done.state()).isEqualTo(PlanExecution.State.FAILED);

        var approvalStep = done.steps().stream().filter(s -> s.id().equals("approval")).findFirst().orElseThrow();
        UUID approval = approval(owner, conversation, proposal.id(), approvalStep.executionId(), approvalStep.capability(), "PENDING", Instant.now().plusSeconds(600));
        var waiting = service.awaitApproval(owner, proposal.id(), done.version(), "approval", approval);
        assertThat(waiting.steps().stream().filter(s -> s.id().equals("approval")).findFirst().orElseThrow().pendingApprovalId()).isEqualTo(approval);
        assertThat(waiting.state()).isEqualTo(PlanExecution.State.FAILED);
    }

    @Test
    void approvalMustBePendingUnexpiredAndBoundToThisOwnerConversationPlanStepAndCapability() {
        proposal = new PlanParser().parse("""
                {"goal":"Arrange a trip","summary":"Prepare together","memoryReferences":[],
                 "constraints":[],"missingInformation":[],"steps":[
                 {"id":"approval","title":"Update event","capability":"calendar.update","dependsOn":[]}]}
                """, Set.of());
        var created = service.create(owner, conversation, proposal);
        // The catalog has no connected approval-required capability yet.
        jdbc.update("update plan_execution_steps set state = 'PROPOSED' where plan_id = ? and step_id = 'approval'", proposal.id().toString());
        UUID executionId = created.steps().get(0).executionId();
        UUID otherConversation = UUID.randomUUID();
        UUID foreignConversation = UUID.randomUUID();
        var now = Timestamp.from(Instant.now());
        jdbc.update("insert into conversations (id, owner_hash, title, created_at, updated_at) values (?, ?, ?, ?, ?), (?, ?, ?, ?, ?)",
                otherConversation.toString(), owner, "Other", now, now, foreignConversation.toString(), stranger, "Foreign", now, now);
        Instant future = Instant.now().plusSeconds(600);
        var invalid = List.of(UUID.randomUUID(),
                approval(stranger, foreignConversation, proposal.id(), executionId, "calendar.update", "PENDING", future),
                approval(owner, conversation, proposal.id(), executionId, "calendar.update", "PENDING", Instant.now().minusSeconds(1)),
                approval(owner, otherConversation, proposal.id(), executionId, "calendar.update", "PENDING", future),
                approval(owner, conversation, UUID.randomUUID(), executionId, "calendar.update", "PENDING", future),
                approval(owner, conversation, proposal.id(), UUID.randomUUID(), "calendar.update", "PENDING", future),
                approval(owner, conversation, proposal.id(), executionId, "gmail.send", "PENDING", future),
                approval(owner, conversation, proposal.id(), executionId, "calendar.update", "REJECTED", future));
        for (UUID id : invalid) {
            assertThatThrownBy(() -> service.awaitApproval(owner, proposal.id(), 0, "approval", id))
                    .isInstanceOf(IllegalArgumentException.class);
            var unchanged = service.find(owner, proposal.id());
            assertThat(unchanged.version()).isZero();
            assertThat(unchanged.steps().get(0).pendingApprovalId()).isNull();
        }
        UUID valid = approval(owner, conversation, proposal.id(), executionId, "calendar.update", "PENDING", future);
        var waiting = service.awaitApproval(owner, proposal.id(), 0, "approval", valid);
        assertThat(waiting.state()).isEqualTo(PlanExecution.State.AWAITING_APPROVAL);
        assertThat(waiting.steps().get(0).pendingApprovalId()).isEqualTo(valid);
    }

    @Test
    void rejectsForeignDeletedSuggestedAndSupersededMemoryReferences() {
        UUID active = memory(owner, "active", null);
        UUID foreign = memory(stranger, "active", null);
        UUID suggested = memory(owner, "suggested", null);
        UUID superseded = memory(owner, "active", active);
        UUID deleted = memory(owner, "active", null);
        jdbc.update("delete from personal_memories where id = ?", deleted.toString());

        for (UUID invalid : List.of(foreign, suggested, superseded, deleted, UUID.randomUUID())) {
            Plan candidate = withReferences(active, invalid);
            assertThatThrownBy(() -> service.create(owner, conversation, candidate))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(service.list(owner, conversation)).isEmpty();
        }
    }

    @Test
    void preservesValidatedProposalWhenReferencedMemoryChangesAfterCreation() {
        UUID active = memory(owner, "active", null);
        Plan original = withReferences(active);
        var created = service.create(owner, conversation, original);
        assertThat(created.proposal()).isEqualTo(original);

        jdbc.update("delete from personal_memories where id = ? and owner_hash = ?", active.toString(), owner);
        assertThat(service.find(owner, original.id()).proposal()).isEqualTo(original);
        var running = service.start(owner, original.id(), created.version(), "first", null);
        assertThat(running.proposal()).isEqualTo(original);
        assertThatThrownBy(() -> service.create(owner, conversation, withReferences(active)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Plan withReferences(UUID... ids) {
        return new Plan(UUID.randomUUID(), proposal.goal(), proposal.summary(),
                java.util.Arrays.stream(ids).map(id -> new Plan.MemoryReference(id, "Relevant context")).toList(),
                proposal.constraints(), proposal.missingInformation(), proposal.steps(), false);
    }

    private UUID memory(String memoryOwner, String status, UUID supersededBy) {
        UUID id = UUID.randomUUID();
        var now = Timestamp.from(Instant.now());
        jdbc.update("insert into personal_memories (id, owner_hash, content, source, status, confidence, superseded_by, created_at, updated_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)", id.toString(), memoryOwner, "Travel preference",
                "user_explicit", status, 1.0, supersededBy == null ? null : supersededBy.toString(), now, now);
        return id;
    }

    private UUID trace(PlanExecution execution, String id, String status) {
        var step = execution.steps().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
        UUID trace = UUID.randomUUID();
        var now = Timestamp.from(Instant.now());
        jdbc.update("insert into tool_traces (id, owner_hash, conversation_id, request_id, plan_id, step_id, tool_name, capability, status, argument_summary, result_summary, requested_at, authorization_decision, started_at, finished_at, duration_ms) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                trace.toString(), owner, conversation.toString(), UUID.randomUUID().toString(), proposal.id().toString(),
                step.executionId().toString(), "test", step.capability(), status, "{}", "{}", now, "ALLOW", now, now, 0);
        return trace;
    }

    private UUID approval(String approvalOwner, UUID approvalConversation, UUID planId, UUID stepId,
            String capability, String status, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into action_approvals (id, owner_hash, conversation_id, request_id, tool_name, capability, effect, arguments_json, arguments_digest, proposal_identity, definition_digest, expires_at, status, plan_id, step_id) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id.toString(), approvalOwner, approvalConversation.toString(), UUID.randomUUID().toString(), "test", capability,
                "WRITE", "{}", "0".repeat(64), "0".repeat(32) + id.toString().replace("-", ""),
                "0".repeat(64), Timestamp.from(expiresAt), status, planId.toString(), stepId.toString());
        return id;
    }
}
