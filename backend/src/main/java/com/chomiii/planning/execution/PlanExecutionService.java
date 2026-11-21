package com.chomiii.planning.execution;

import com.chomiii.planning.Plan;
import com.chomiii.planning.PlanParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.HashSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.chomiii.planning.execution.PlanExecution.State;

/** Server-side persistence boundary; it is not an execution or authorization API. */
@Service
public class PlanExecutionService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PlanExecutionService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public PlanExecution create(String owner, UUID conversationId, Plan proposal) {
        lockOwner(owner);
        lockConversation(owner, conversationId);
        var authorizedMemoryIds = new HashSet<UUID>();
        for (var reference : proposal.memoryReferences()) {
            var matches = jdbc.query("select id from personal_memories where id = ? and owner_hash = ? "
                            + "and status = 'active' and superseded_by is null for update",
                    (r, n) -> UUID.fromString(r.getString(1)), reference.id().toString(), owner);
            if (matches.isEmpty()) throw new IllegalArgumentException("Memory reference not available");
            authorizedMemoryIds.add(matches.get(0));
        }
        // Re-validate even though callers normally supply PlanningService output: Plan is publicly constructible.
        var draft = new PlanParser.Draft(proposal.goal(), proposal.summary(),
                proposal.memoryReferences().stream().map(r -> new PlanParser.DraftMemory(r.id().toString(), r.relevance())).toList(),
                proposal.constraints(), proposal.missingInformation().stream().map(q ->
                        new PlanParser.DraftQuestion(q.id(), q.question(), q.kind(), q.blocks())).toList(),
                proposal.steps().stream().map(s -> new PlanParser.DraftStep(s.id(), s.title(), s.capability(), s.dependsOn(), s.arguments())).toList());
        Plan checked = new PlanParser().parse(write(draft), authorizedMemoryIds);
        Plan normalized = new Plan(proposal.id(), checked.goal(), checked.summary(), checked.memoryReferences(),
                checked.constraints(), checked.missingInformation(), checked.steps(), false);
        if (!normalized.equals(proposal)) throw new IllegalArgumentException("Proposal was not validated");
        Instant now = Instant.now();
        int inserted = jdbc.update("insert into plan_executions (id, owner_hash, conversation_id, proposal_json, state, version, created_at, updated_at) "
                        + "select ?, owner_hash, id, ?, ?, 0, ?, ? from conversations where id = ? and owner_hash = ?",
                proposal.id().toString(), write(proposal), initialState(proposal).name(), Timestamp.from(now), Timestamp.from(now),
                conversationId.toString(), owner);
        if (inserted != 1) throw new IllegalArgumentException("Conversation not found");
        for (var step : proposal.steps()) {
            jdbc.update("insert into plan_execution_steps (plan_id, step_id, execution_id, state) values (?, ?, ?, ?)",
                    proposal.id().toString(), step.id(), UUID.randomUUID().toString(), "blocked".equals(step.status()) ? State.BLOCKED.name() : State.PROPOSED.name());
        }
        return findLocked(owner, proposal.id());
    }

    @Transactional(readOnly = true)
    public PlanExecution find(String owner, UUID planId) {
        return find(owner, planId, false);
    }

    @Transactional(readOnly = true)
    public PlanExecution revisionSnapshot(String owner, UUID conversation, UUID planId, long version) {
        var plan = find(owner, planId);
        checkRevision(plan, conversation, version);
        return plan;
    }

    @Transactional
    public void revise(String owner, UUID conversation, UUID previousId, long version, Plan proposal) {
        lockOwner(owner);
        lockConversation(owner, conversation);
        var previous = findLocked(owner, previousId);
        checkRevision(previous, conversation, version);
        create(owner, conversation, proposal);
        jdbc.update("update plan_executions set revised_from_id = ? where id = ? and owner_hash = ?",
                previousId.toString(), proposal.id().toString(), owner);
        cancel(owner, previousId, version);
    }

    private static void checkRevision(PlanExecution plan, UUID conversation, long version) {
        if (!plan.conversationId().equals(conversation)) throw new IllegalArgumentException("Plan not found");
        if (plan.version() != version) throw new IllegalStateException("Plan version conflict");
        if (plan.state() == State.COMPLETED || plan.state() == State.CANCELLED || plan.state() == State.RUNNING
                || plan.steps().stream().anyMatch(s -> s.state() == State.RUNNING))
            throw new IllegalStateException("Plan cannot be revised");
    }

    private PlanExecution findLocked(String owner, UUID planId) {
        return find(owner, planId, true);
    }

    private PlanExecution find(String owner, UUID planId, boolean locked) {
        var found = jdbc.query("select conversation_id, proposal_json, state, version, created_at, updated_at from plan_executions where id = ? and owner_hash = ?"
                        + (locked ? " for update" : ""),
                (r, n) -> new PlanExecution(owner, UUID.fromString(r.getString(1)), read(r.getString(2)), State.valueOf(r.getString(3)),
                        r.getLong(4), r.getTimestamp(5).toInstant(), r.getTimestamp(6).toInstant(), List.of()), planId.toString(), owner);
        if (found.isEmpty()) throw new IllegalArgumentException("Plan not found");
        var base = found.get(0);
        var rows = jdbc.query("select s.step_id, s.state, s.arguments_ref, s.observation_ref, s.pending_approval_id, s.execution_id, s.observation_json from plan_execution_steps s "
                        + "join plan_executions p on p.id = s.plan_id where p.id = ? and p.owner_hash = ?" + (locked ? " for update" : ""),
                (r, n) -> new Object[] {r.getString(1), State.valueOf(r.getString(2)), r.getString(3), uuid(r.getString(4)), uuid(r.getString(5)), uuid(r.getString(6)), readObservation(r.getString(7))},
                planId.toString(), owner);
        var steps = base.proposal().steps().stream().map(s -> {
            Object[] row = rows.stream().filter(r -> r[0].equals(s.id())).findFirst().orElseThrow();
            return new PlanExecution.Step(s.id(), (UUID) row[5], s.capability(), (State) row[1], (String) row[2], (UUID) row[3], (UUID) row[4], (PlanExecution.Observation) row[6]);
        }).toList();
        return new PlanExecution(owner, base.conversationId(), base.proposal(), base.state(), base.version(), base.createdAt(), base.updatedAt(), steps);
    }

    @Transactional(readOnly = true)
    public List<PlanExecution> list(String owner, UUID conversationId) {
        return jdbc.query("select id from plan_executions where owner_hash = ? and conversation_id = ? order by created_at, id",
                (r, n) -> UUID.fromString(r.getString(1)), owner, conversationId.toString())
                .stream().map(id -> find(owner, id)).toList();
    }

    /** Records an approval request created by server code; it does not grant approval. */
    @Transactional
    public PlanExecution awaitApproval(String owner, UUID planId, long version, String stepId, UUID approvalId) {
        var plan = lock(owner, planId, version);
        var step = step(plan, stepId);
        var proposed = plan.proposal().steps().stream().filter(s -> s.id().equals(stepId)).findFirst().orElseThrow();
        if (approvalId == null || step.state() != State.PROPOSED
                || !"required".equals(proposed.approvalHint())
                || proposed.dependsOn().stream().anyMatch(id -> step(plan, id).state() != State.COMPLETED))
            throw new IllegalStateException("Step cannot await approval");
        boolean todo = step.capability().equals("todo.create") || step.capability().equals("todo.complete");
        var expiry = jdbc.query("select expires_at from action_approvals where id = ? and owner_hash = ? "
                        + "and conversation_id = ? and plan_id = ? and step_id = ? and capability = ? "
                        + (todo ? "and tool_name = ? and effect = 'WRITE' " : "") + "and status = 'PENDING' for update",
                (r, n) -> r.getTimestamp(1).toInstant(), todo
                        ? new Object[] {approvalId.toString(), owner, plan.conversationId().toString(), planId.toString(), step.executionId().toString(), "todo.write", step.capability()}
                        : new Object[] {approvalId.toString(), owner, plan.conversationId().toString(), planId.toString(), step.executionId().toString(), step.capability()});
        if (expiry.isEmpty() || !expiry.get(0).isAfter(Instant.now())) throw new IllegalArgumentException("Approval not available");
        jdbc.update("update plan_execution_steps set state = ?, pending_approval_id = ? where plan_id = ? and step_id = ?",
                State.AWAITING_APPROVAL.name(), approvalId.toString(), planId.toString(), stepId);
        bump(planId, aggregate(owner, planId));
        return findLocked(owner, planId);
    }

    /** Starts only steps which the proposal marked unblocked and not requiring approval. */
    @Transactional
    public PlanExecution start(String owner, UUID planId, long version, String stepId, String argumentsRef) {
        if (argumentsRef != null && !argumentsRef.matches("[A-Za-z0-9._:-]{1,128}")) throw new IllegalArgumentException("Invalid arguments reference");
        var plan = lock(owner, planId, version);
        var step = step(plan, stepId);
        var proposalStep = plan.proposal().steps().stream().filter(s -> s.id().equals(stepId)).findFirst().orElseThrow();
        if (step.state() != State.PROPOSED || !"not_required".equals(proposalStep.approvalHint())
                || proposalStep.dependsOn().stream().anyMatch(id -> step(plan, id).state() != State.COMPLETED))
            throw new IllegalStateException("Step cannot start");
        jdbc.update("update plan_execution_steps set state = ?, arguments_ref = ? where plan_id = ? and step_id = ?",
                State.RUNNING.name(), argumentsRef, planId.toString(), stepId);
        bump(planId, State.RUNNING);
        return findLocked(owner, planId);
    }

    /** Accepts only an existing successful server trace bound to this owner, conversation, plan, step and capability. */
    @Transactional
    public PlanExecution completeFromObservation(String owner, UUID planId, long version, String stepId, UUID observationId) {
        return completeFromObservation(owner, planId, version, stepId, observationId, null);
    }

    @Transactional
    PlanExecution completeFromObservation(String owner, UUID planId, long version, String stepId, UUID observationId, PlanExecution.Observation observation) {
        var plan = lock(owner, planId, version);
        var step = step(plan, stepId);
        if (step.state() != State.RUNNING || observationId == null) throw new IllegalStateException("Step is not running");
        boolean todo = step.capability().startsWith("todo.");
        String capability = step.capability().equals("todo.list") ? "todo.read" : todo ? "todo.write" : step.capability();
        var observations = jdbc.query("select id from tool_traces where id = ? and owner_hash = ? and conversation_id = ? "
                        + "and plan_id = ? and step_id = ? and capability = ? "
                        + (todo ? "and tool_name = ? and effect = ? " : "")
                        + (todo && !step.capability().equals("todo.list") ? "and approval_id = ? " : "") + "and status = 'succeeded' "
                        + "and authorization_decision = 'ALLOW' and started_at is not null and finished_at is not null for update",
                (r, n) -> r.getString(1), todo
                        ? (step.capability().equals("todo.list")
                            ? new Object[] {observationId.toString(), owner, plan.conversationId().toString(), planId.toString(), step.executionId().toString(), capability, step.capability(), "READ"}
                            : new Object[] {observationId.toString(), owner, plan.conversationId().toString(), planId.toString(), step.executionId().toString(), capability, step.capability(), "WRITE", step.pendingApprovalId() == null ? null : step.pendingApprovalId().toString()})
                        : new Object[] {observationId.toString(), owner, plan.conversationId().toString(), planId.toString(), step.executionId().toString(), capability});
        if (observations.isEmpty()) throw new IllegalArgumentException("Observation not found");
        if (observation != null && !step.capability().equals("todo.list")) throw new IllegalArgumentException("Invalid observation");
        jdbc.update("update plan_execution_steps set state = ?, observation_ref = ?, observation_json = ?, pending_approval_id = null where plan_id = ? and step_id = ?",
                State.COMPLETED.name(), observationId.toString(), observation == null ? null : write(observation), planId.toString(), stepId);
        bump(planId, aggregate(owner, planId));
        return findLocked(owner, planId);
    }

    @Transactional
    public PlanExecution fail(String owner, UUID planId, long version, String stepId) {
        var plan = lock(owner, planId, version);
        if (step(plan, stepId).state() != State.RUNNING) throw new IllegalStateException("Step is not running");
        jdbc.update("update plan_execution_steps set state = ?, pending_approval_id = null where plan_id = ? and step_id = ?",
                State.FAILED.name(), planId.toString(), stepId);
        bump(planId, aggregate(owner, planId));
        return blockDependents(owner, planId, false);
    }

    @Transactional
    public PlanExecution cancel(String owner, UUID planId, long version) {
        var plan = lock(owner, planId, version);
        if (plan.state() == State.COMPLETED || plan.state() == State.CANCELLED || plan.steps().stream().anyMatch(s -> s.state() == State.RUNNING))
            throw new IllegalStateException("Plan cannot be cancelled");
        jdbc.update("update action_approvals set status = 'CANCELLED' where plan_id = ? and owner_hash = ? and conversation_id = ? and status = 'PENDING'",
                planId.toString(), owner, plan.conversationId().toString());
        jdbc.update("update tool_traces set status = 'cancelled', error_code = 'PLAN_CANCELLED', finished_at = ?, duration_ms = 0 where owner_hash = ? and conversation_id = ? and plan_id = ? and status = 'requested' and approval_id is not null",
                Timestamp.from(Instant.now()), owner, plan.conversationId().toString(), planId.toString());
        jdbc.update("update plan_execution_steps set state = ?, pending_approval_id = null where plan_id = ? and state in (?, ?, ?)",
                State.CANCELLED.name(), planId.toString(), State.PROPOSED.name(), State.BLOCKED.name(), State.AWAITING_APPROVAL.name());
        bump(planId, State.CANCELLED);
        return findLocked(owner, planId);
    }

    @Transactional
    public PlanExecution cancelForConversation(String owner, UUID conversationId, UUID planId) {
        lockOwner(owner);
        lockConversation(owner, conversationId);
        var plan = findLocked(owner, planId);
        if (!plan.conversationId().equals(conversationId)) throw new IllegalArgumentException("Plan not found");
        return cancel(owner, planId, plan.version());
    }

    /** Locks the owner, conversation, plan and steps for one bounded run segment. */
    @Transactional
    public PlanExecution prepareRun(String owner, UUID conversationId, UUID planId) {
        lockOwner(owner);
        lockConversation(owner, conversationId);
        var plan = findLocked(owner, planId);
        if (!plan.conversationId().equals(conversationId)) throw new IllegalArgumentException("Plan not found");
        if (plan.state() == State.CANCELLED || plan.state() == State.COMPLETED) return plan;
        for (var step : plan.steps()) {
            if (step.state() != State.AWAITING_APPROVAL) continue;
            var closed = jdbc.query("select status, expires_at from action_approvals where id = ? and owner_hash = ? and conversation_id = ? and plan_id = ? and step_id = ? for update",
                    (r, n) -> !"PENDING".equals(r.getString(1)) || !r.getTimestamp(2).toInstant().isAfter(Instant.now()),
                    step.pendingApprovalId().toString(), owner, conversationId.toString(), planId.toString(), step.executionId().toString());
            if (closed.isEmpty()) throw new IllegalStateException("Approval unavailable");
            if (closed.get(0)) {
                jdbc.update("update action_approvals set status = 'CANCELLED' where id = ? and status = 'PENDING'", step.pendingApprovalId().toString());
                jdbc.update("update tool_traces set status = 'cancelled', error_code = 'APPROVAL_EXPIRED', finished_at = ?, duration_ms = 0 where owner_hash = ? and conversation_id = ? and plan_id = ? and step_id = ? and approval_id = ? and status = 'requested'",
                        Timestamp.from(Instant.now()), owner, conversationId.toString(), planId.toString(), step.executionId().toString(), step.pendingApprovalId().toString());
                jdbc.update("update plan_execution_steps set state = 'FAILED', pending_approval_id = null where plan_id = ? and step_id = ?", planId.toString(), step.id());
                bump(planId, aggregate(owner, planId));
            }
        }
        return blockUnavailableAndDependents(owner, planId);
    }

    @Transactional
    public PlanExecution startApproved(String owner, UUID planId, UUID stepExecutionId, UUID approvalId) {
        var current = find(owner, planId);
        var plan = lock(owner, planId, current.version());
        var step = plan.steps().stream().filter(s -> s.executionId().equals(stepExecutionId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Step not found"));
        if (step.state() != State.AWAITING_APPROVAL || !approvalId.equals(step.pendingApprovalId())
                || !List.of("todo.create", "todo.complete").contains(step.capability()))
            throw new IllegalStateException("Step is not awaiting this approval");
        var valid = jdbc.query("select id from action_approvals where id = ? and owner_hash = ? and conversation_id = ? and plan_id = ? and step_id = ? and tool_name = ? and capability = 'todo.write' and effect = 'WRITE' and status = 'APPROVED' and expires_at > ? for update",
                (r, n) -> r.getString(1), approvalId.toString(), owner, plan.conversationId().toString(), planId.toString(), stepExecutionId.toString(), step.capability(), Timestamp.from(Instant.now()));
        if (valid.isEmpty()) throw new IllegalStateException("Approval is not executing");
        jdbc.update("update plan_execution_steps set state = 'RUNNING' where plan_id = ? and step_id = ?", planId.toString(), step.id());
        bump(planId, State.RUNNING);
        return findLocked(owner, planId);
    }

    @Transactional
    public void rejectApproval(String owner, UUID planId, UUID stepExecutionId, UUID approvalId) {
        var current = find(owner, planId);
        var plan = lock(owner, planId, current.version());
        var step = plan.steps().stream().filter(s -> s.executionId().equals(stepExecutionId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Step not found"));
        if (step.state() != State.AWAITING_APPROVAL || !approvalId.equals(step.pendingApprovalId()))
            throw new IllegalStateException("Step is not awaiting this approval");
        var rejected = jdbc.query("select id from action_approvals where id = ? and owner_hash = ? and conversation_id = ? and plan_id = ? and step_id = ? and status = 'REJECTED' for update",
                (r, n) -> r.getString(1), approvalId.toString(), owner, plan.conversationId().toString(), planId.toString(), stepExecutionId.toString());
        if (rejected.isEmpty()) throw new IllegalStateException("Approval not rejected");
        jdbc.update("update plan_execution_steps set state = 'FAILED', pending_approval_id = null where plan_id = ? and step_id = ?", planId.toString(), step.id());
        bump(planId, aggregate(owner, planId));
        blockUnavailableAndDependents(owner, planId);
    }

    @Transactional
    public PlanExecution blockUnavailable(String owner, UUID planId, long version, String stepId) {
        var plan = lock(owner, planId, version);
        if (step(plan, stepId).state() != State.PROPOSED) throw new IllegalStateException("Step cannot be blocked");
        jdbc.update("update plan_execution_steps set state = 'BLOCKED' where plan_id = ? and step_id = ?", planId.toString(), stepId);
        bump(planId, aggregate(owner, planId));
        return blockUnavailableAndDependents(owner, planId);
    }

    private PlanExecution blockUnavailableAndDependents(String owner, UUID planId) {
        return blockDependents(owner, planId, true);
    }

    private PlanExecution blockDependents(String owner, UUID planId, boolean blockUnsupported) {
        var plan = findLocked(owner, planId);
        for (var proposed : plan.proposal().steps()) {
            var step = step(plan, proposed.id());
            if (step.state() != State.PROPOSED) continue;
            boolean unsupported = blockUnsupported && !List.of("todo.list", "todo.create", "todo.complete").contains(step.capability());
            var snapshot = plan;
            boolean blockedDependency = proposed.dependsOn().stream().anyMatch(id -> {
                var state = step(snapshot, id).state();
                return state == State.FAILED || state == State.BLOCKED || state == State.CANCELLED;
            });
            if (unsupported || blockedDependency) {
                jdbc.update("update plan_execution_steps set state = 'BLOCKED' where plan_id = ? and step_id = ?", planId.toString(), step.id());
                bump(planId, aggregate(owner, planId));
                plan = findLocked(owner, planId);
            }
        }
        return plan;
    }

    private PlanExecution lock(String owner, UUID id, long version) {
        lockOwner(owner);
        // This lookup only locates the conversation; all authoritative reads below are locking reads.
        var conversations = jdbc.query("select conversation_id from plan_executions where id = ? and owner_hash = ?",
                (r, n) -> UUID.fromString(r.getString(1)), id.toString(), owner);
        if (conversations.isEmpty()) throw new IllegalArgumentException("Plan not found");
        lockConversation(owner, conversations.get(0));
        var rows = jdbc.query("select version from plan_executions where id = ? and owner_hash = ? for update", (r, n) -> r.getLong(1), id.toString(), owner);
        if (rows.isEmpty()) throw new IllegalArgumentException("Plan not found");
        if (rows.get(0) != version) throw new IllegalStateException("Plan version conflict");
        var plan = findLocked(owner, id);
        if (plan.state() == State.CANCELLED || plan.state() == State.COMPLETED) throw new IllegalStateException("Plan is terminal");
        return plan;
    }

    private void lockOwner(String owner) {
        if (jdbc.query("select owner_hash from browser_sessions where owner_hash = ? for update",
                (r, n) -> r.getString(1), owner).isEmpty()) throw new IllegalArgumentException("Owner not found");
    }

    private void lockConversation(String owner, UUID id) {
        if (jdbc.query("select id from conversations where id = ? and owner_hash = ? for update",
                (r, n) -> r.getString(1), id.toString(), owner).isEmpty()) throw new IllegalArgumentException("Conversation not found");
    }

    private State aggregate(String owner, UUID id) {
        var steps = findLocked(owner, id).steps();
        if (steps.stream().anyMatch(s -> s.state() == State.RUNNING)) return State.RUNNING;
        if (steps.stream().anyMatch(s -> s.state() == State.FAILED)) return State.FAILED;
        if (steps.stream().anyMatch(s -> s.state() == State.AWAITING_APPROVAL)) return State.AWAITING_APPROVAL;
        if (!steps.isEmpty() && steps.stream().allMatch(s -> s.state() == State.COMPLETED)) return State.COMPLETED;
        if (steps.stream().anyMatch(s -> s.state() == State.BLOCKED)) return State.BLOCKED;
        return State.PROPOSED;
    }

    private void bump(UUID id, State state) {
        jdbc.update("update plan_executions set state = ?, version = version + 1, updated_at = ? where id = ?",
                state.name(), Timestamp.from(Instant.now()), id.toString());
    }

    private static State initialState(Plan p) {
        return p.steps().isEmpty() || p.steps().stream().anyMatch(s -> "blocked".equals(s.status())) ? State.BLOCKED : State.PROPOSED;
    }
    private static PlanExecution.Step step(PlanExecution p, String id) {
        return p.steps().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Step not found"));
    }
    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
    private PlanExecution.Observation readObservation(String value) {
        if (value == null) return null;
        try { return json.readValue(value, PlanExecution.Observation.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Invalid stored observation", e); }
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalArgumentException("Invalid proposal", e); }
    }
    private Plan read(String value) {
        try { return json.readValue(value, Plan.class); } catch (JsonProcessingException e) { throw new IllegalStateException("Invalid stored proposal", e); }
    }
}
