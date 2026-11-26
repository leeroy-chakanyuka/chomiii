package com.chomiii.approval;

import com.chomiii.todo.TodoService;
import com.chomiii.planning.execution.PlanExecutionService;
import com.chomiii.tools.ToolDefinition;
import com.chomiii.tools.ToolExecutionContext;
import com.chomiii.tools.ToolRegistry;
import com.chomiii.tools.ToolSchemaValidator;
import com.chomiii.tools.trace.ToolTraceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApprovalFacade {
    private final ApprovalService approvals;
    private final ToolRegistry tools;
    private final ToolTraceService traces;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<PlanExecutionService> plans;

    public ApprovalFacade(ApprovalService approvals, ToolRegistry tools, ToolTraceService traces,
            ObjectMapper json, JdbcTemplate jdbc, ObjectProvider<PlanExecutionService> plans) {
        this.approvals = approvals;
        this.tools = tools;
        this.traces = traces;
        this.json = json;
        this.jdbc = jdbc;
        this.plans = plans;
    }

    @Transactional
    public ApprovalView propose(ToolExecutionContext context, ToolDefinition definition, Map<String, Object> arguments, UUID traceId) {
        return propose(context, definition, arguments, traceId, null, null);
    }

    @Transactional
    public ApprovalView proposeForPlan(ToolExecutionContext context, ToolDefinition definition, Map<String, Object> arguments,
            UUID traceId, UUID planId, UUID stepExecutionId) {
        java.util.Objects.requireNonNull(planId);
        java.util.Objects.requireNonNull(stepExecutionId);
        return propose(context, definition, arguments, traceId, planId, stepExecutionId);
    }

    // Run before entering the transactional proposal method, so expected bad input cannot mark the run rollback-only.
    public boolean validPlanProposal(ToolExecutionContext context, ToolDefinition definition, Map<String, Object> arguments) {
        try {
            ToolSchemaValidator.validate(definition.parameters(), arguments);
            if (context.apiKey() != null && !context.apiKey().isEmpty()
                    && arguments.values().stream().anyMatch(value -> value instanceof String text && text.contains(context.apiKey()))) return false;
            Map<String, Object> normalized = normalize(context.owner(), context.conversationId(), definition.name(), arguments);
            return approvals.validArguments(definition, normalized);
        } catch (IllegalArgumentException | java.time.DateTimeException invalid) {
            return false;
        }
    }

    private ApprovalView propose(ToolExecutionContext context, ToolDefinition definition, Map<String, Object> arguments,
            UUID traceId, UUID planId, UUID stepExecutionId) {
        approvals.lockConversation(context.owner(), context.conversationId());
        if (planId == null) requireRequestStatus(context.owner(), context.conversationId(), context.requestId(), "pending");
        ToolDefinition registered = tools.definition(definition.name()).orElseThrow(() -> new IllegalArgumentException("Tool unavailable"));
        if (!registered.equals(definition) || !"todo.write".equals(definition.capability())
                || definition.effect() != ToolDefinition.Effect.WRITE
                || !List.of("todo.create", "todo.complete").contains(definition.name())) throw new IllegalArgumentException("Tool unavailable");
        ToolSchemaValidator.validate(definition.parameters(), arguments);
        if (context.apiKey() != null && !context.apiKey().isEmpty()
                && arguments.values().stream().anyMatch(value -> value instanceof String text && text.contains(context.apiKey())))
            throw new IllegalArgumentException("Credential values are not allowed");
        Map<String, Object> normalized = normalize(context.owner(), context.conversationId(), definition.name(), arguments);
        String preview = preview(context.owner(), definition.name(), normalized);
        var action = approvals.create(context.owner(), context.conversationId(), context.requestId(), definition,
                normalized, Instant.now().plusSeconds(300), planId, stepExecutionId);
        action = approvals.setPreview(context.owner(), context.conversationId(), action.id(), preview);
        if (action.status() != ApprovalService.Status.PENDING || !action.expiresAt().isAfter(Instant.now()))
            throw new IllegalStateException("Proposal is no longer pending");
        boolean duplicate = traces.pendingForApproval(context.owner(), context.conversationId(), action.id()).isPresent();
        var matchingTrace = jdbc.query("select id from tool_traces where id = ? and owner_hash = ? and conversation_id = ? and request_id = ? "
                        + (planId == null ? "and plan_id is null and step_id is null" : "and plan_id = ? and step_id = ?") + " for update",
                (row, index) -> row.getString(1), planId == null
                        ? new Object[] {traceId.toString(), context.owner(), context.conversationId().toString(), context.requestId().toString()}
                        : new Object[] {traceId.toString(), context.owner(), context.conversationId().toString(), context.requestId().toString(), planId.toString(), stepExecutionId.toString()});
        if (matchingTrace.size() != 1) throw new IllegalStateException("Trace does not match proposal");
        if (planId == null) {
            traces.markAwaitingApproval(context.owner(), traceId, context.conversationId(), context.requestId(),
                    definition.name(), definition.capability(), definition.effect().name(), action.id());
        } else if (jdbc.update("update tool_traces set approval_id = ? where id = ? and owner_hash = ? and conversation_id = ? "
                        + "and request_id = ? and plan_id = ? and step_id = ? and tool_name = ? and capability = ? and effect = ? "
                        + "and status = 'requested' and authorization_decision is null and approval_id is null",
                action.id().toString(), traceId.toString(), context.owner(), context.conversationId().toString(),
                context.requestId().toString(), planId.toString(), stepExecutionId.toString(), definition.name(),
                definition.capability(), definition.effect().name()) != 1) {
            throw new IllegalStateException("Tool trace is not awaiting a decision");
        }
        if (duplicate) {
            traces.recordFinished(context.owner(), traceId, "cancelled", "DUPLICATE_APPROVAL", Map.of());
        }
        if (planId == null) requireRequestStatus(context.owner(), context.conversationId(), context.requestId(), "pending");
        return view(action);
    }

    @Transactional(readOnly = true)
    public List<ApprovalView> list(String owner, UUID conversationId) {
        return approvals.list(owner, conversationId).stream().filter(a -> a.planId() != null
                || a.status() != ApprovalService.Status.PENDING || committedRequest(owner, conversationId, a.requestId()))
                .map(this::view).toList();
    }

    @Transactional
    public ApprovalView approve(String owner, UUID conversationId, UUID id) {
        return approveAndPlanToResume(owner, conversationId, id).view();
    }

    public record ApprovalDecision(ApprovalView view, UUID planToResume) {}

    @Transactional
    public ApprovalDecision approveAndPlanToResume(String owner, UUID conversationId, UUID id) {
        var action = approvals.lock(owner, conversationId, id);
        if (action.status() == ApprovalService.Status.CONSUMED) return new ApprovalDecision(view(action), null);
        if (action.preview() == null) throw new IllegalStateException("Action unavailable");
        if (action.planId() == null) requireRequestStatus(owner, conversationId, action.requestId(), "complete");
        ToolDefinition definition = tools.definition(action.toolName()).orElseThrow(() -> new IllegalStateException("Tool unavailable"));
        if (!"todo.write".equals(definition.capability()) || definition.effect() != ToolDefinition.Effect.WRITE
                || !List.of("todo.create", "todo.complete").contains(definition.name())) throw new IllegalStateException("Tool unavailable");
        Map<String, Object> arguments = arguments(action.argumentsJson());
        UUID trace = traces.pendingForApproval(owner, conversationId, id)
                .orElseThrow(() -> new IllegalStateException("Pending trace unavailable"));
        approvals.approveAndExecute(owner, conversationId, id, definition, arguments, approved -> {
            var running = approved.planId() == null ? null : plans.getObject().startApproved(owner, approved.planId(), approved.stepId(), id);
            traces.recordAuthorized(owner, trace, id);
            traces.recordStarted(owner, trace);
            var context = new ToolExecutionContext(owner, conversationId, approved.requestId(), null, false, ignored -> {}, id, approved.planId());
            var result = tools.executeAuthorized(context, definition.name(), arguments);
            Object reference = result.output().get("id");
            if (!(reference instanceof String text)) throw new IllegalStateException("Invalid tool result");
            UUID resultId = UUID.fromString(text);
            traces.recordFinished(owner, trace, "succeeded", null, result.output());
            if (running != null) {
                String stepId = running.steps().stream().filter(step -> step.executionId().equals(approved.stepId()))
                        .findFirst().orElseThrow().id();
                plans.getObject().completeFromObservation(owner, approved.planId(), running.version(), stepId, trace);
            }
            finishPendingTraces(owner, conversationId, id, "cancelled", "DUPLICATE_APPROVAL");
            return resultId;
        });
        return new ApprovalDecision(view(approvals.lock(owner, conversationId, id)), action.planId());
    }

    @Transactional
    public ApprovalView reject(String owner, UUID conversationId, UUID id) {
        var current = approvals.lock(owner, conversationId, id);
        if (current.status() == ApprovalService.Status.REJECTED) return view(current);
        if (traces.pendingForApproval(owner, conversationId, id).isEmpty())
            throw new IllegalStateException("Pending trace unavailable");
        var rejected = approvals.reject(owner, conversationId, id);
        if (current.planId() != null) plans.getObject().rejectApproval(owner, current.planId(), current.stepId(), id);
        finishPendingTraces(owner, conversationId, id, "denied", "USER_REJECTED");
        return view(rejected);
    }

    @Transactional
    public void cancelRequest(String owner, UUID conversationId, UUID requestId) {
        closeRequest(owner, conversationId, requestId, "interrupted");
    }

    @Transactional
    public void failRequest(String owner, UUID conversationId, UUID requestId) {
        closeRequest(owner, conversationId, requestId, "failed");
    }

    private void closeRequest(String owner, UUID conversationId, UUID requestId, String status) {
        approvals.lockConversation(owner, conversationId);
        var states = jdbc.query("select status from messages where conversation_id = ? and request_id = ? and role = 'assistant' for update",
                (row, index) -> row.getString(1), conversationId.toString(), requestId.toString());
        if (states.size() != 1 || "complete".equals(states.get(0))) return;
        jdbc.update("update messages set status = ? where conversation_id = ? and request_id = ? and role = 'assistant' and status = 'pending'",
                status, conversationId.toString(), requestId.toString());
        jdbc.update("update conversations set active_request_id = null where id = ? and owner_hash = ? and active_request_id = ?",
                conversationId.toString(), owner, requestId.toString());
        var actions = approvals.list(owner, conversationId);
        for (var action : actions) {
            if (action.planId() == null && action.requestId().equals(requestId) && action.status() == ApprovalService.Status.PENDING) {
                var locked = approvals.lock(owner, conversationId, action.id());
                if (locked.status() == ApprovalService.Status.PENDING) {
                    if (locked.expiresAt().isAfter(Instant.now())) approvals.cancel(owner, conversationId, locked.id());
                    finishPendingTraces(owner, conversationId, locked.id(), "cancelled", "REQUEST_CANCELLED");
                }
            }
        }
    }

    private void requireRequestStatus(String owner, UUID conversationId, UUID requestId, String status) {
        var rows = jdbc.query("select m.status from messages m join conversations c on c.id = m.conversation_id "
                        + "where c.id = ? and c.owner_hash = ? and m.request_id = ? and m.role = 'assistant' for update",
                (row, index) -> row.getString(1), conversationId.toString(), owner, requestId.toString());
        if (rows.size() != 1 || !status.equals(rows.get(0))) throw new IllegalStateException("Request is not in the required state");
        if ("pending".equals(status)) {
            Integer active = jdbc.queryForObject("select count(*) from conversations where id = ? and owner_hash = ? and active_request_id = ?",
                    Integer.class, conversationId.toString(), owner, requestId.toString());
            if (active == null || active != 1) throw new IllegalStateException("Request is not active");
        }
    }

    private boolean committedRequest(String owner, UUID conversationId, UUID requestId) {
        Integer count = jdbc.queryForObject("select count(*) from messages m join conversations c on c.id = m.conversation_id "
                        + "where c.id = ? and c.owner_hash = ? and m.request_id = ? and m.role = 'assistant' and m.status = 'complete'",
                Integer.class, conversationId.toString(), owner, requestId.toString());
        return count != null && count == 1;
    }

    private void finishPendingTraces(String owner, UUID conversationId, UUID id, String status, String error) {
        for (;;) {
            var trace = traces.pendingForApproval(owner, conversationId, id);
            if (trace.isEmpty()) return;
            traces.recordFinished(owner, trace.get(), status, error, Map.of());
        }
    }

    private Map<String, Object> normalize(String owner, UUID conversationId, String name, Map<String, Object> arguments) {
        if ("todo.complete".equals(name)) {
            UUID id = UUID.fromString((String) arguments.get("id"));
            todoTitle(owner, id);
            return Map.of("id", id.toString());
        }
        if (!"todo.create".equals(name)) throw new IllegalArgumentException("Tool unavailable");
        Instant due = arguments.containsKey("dueAt") ? Instant.parse(((String) arguments.get("dueAt")).strip()) : null;
        var todo = TodoService.normalize(new TodoService.CreateTodo((String) arguments.get("title"),
                (String) arguments.get("description"), due, (String) arguments.get("timezone"), conversationId, null));
        var normalized = new LinkedHashMap<String, Object>();
        normalized.put("title", todo.title());
        if (todo.description() != null) normalized.put("description", todo.description());
        if (todo.dueAt() != null) normalized.put("dueAt", todo.dueAt().toString());
        if (todo.timezone() != null) normalized.put("timezone", todo.timezone());
        return normalized;
    }

    private String preview(String owner, String name, Map<String, Object> arguments) {
        if ("todo.complete".equals(name)) return "Complete task: " + quote(todoTitle(owner, UUID.fromString((String) arguments.get("id"))))
                + "\nTask ID: " + arguments.get("id");
        StringBuilder text = new StringBuilder("Create task: ").append(quote((String) arguments.get("title")));
        if (arguments.containsKey("description")) text.append("\nDescription: ").append(quote((String) arguments.get("description")));
        if (arguments.containsKey("dueAt")) text.append("\nDue (UTC): ").append(arguments.get("dueAt"));
        if (arguments.containsKey("timezone")) text.append("\nTime zone: ").append(arguments.get("timezone"));
        return text.toString();
    }

    private String todoTitle(String owner, UUID id) {
        return jdbc.query("select title from personal_todos where owner_hash = ? and id = ?",
                (row, index) -> row.getString(1), owner, id.toString()).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));
    }

    private String quote(String text) {
        try { return json.writeValueAsString(text); }
        catch (JsonProcessingException ex) { throw new IllegalArgumentException("Invalid task"); }
    }

    private Map<String, Object> arguments(String value) {
        try { return json.readValue(value, new TypeReference<Map<String, Object>>() {}); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Invalid stored action"); }
    }

    private ApprovalView view(ApprovalService.Approval action) {
        String status = switch (action.status()) {
            case PENDING -> action.expiresAt().isAfter(Instant.now()) ? "pending" : "expired";
            case APPROVED -> throw new IllegalStateException("Approval is executing");
            case CONSUMED -> "completed";
            case REJECTED -> "rejected";
            case CANCELLED -> "cancelled";
        };
        String result = action.resultRef() == null ? null : action.resultRef().toString();
        if (result != null && action.planId() != null && "todo.create".equals(action.toolName())) {
            Integer created = jdbc.queryForObject("select count(*) from personal_todos where owner_hash = ? and id = ? and idempotency_key = ?",
                    Integer.class, action.owner(), result, action.id().toString());
            result = (created != null && created == 1 ? "Task created. ID: " : "Task already exists. ID: ") + result;
        }
        return new ApprovalView(action.id(), action.requestId(), action.toolName(), action.preview(), action.expiresAt(), status, result, null);
    }
}
