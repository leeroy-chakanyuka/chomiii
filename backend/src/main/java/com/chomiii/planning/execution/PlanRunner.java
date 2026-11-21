package com.chomiii.planning.execution;

import com.chomiii.approval.ApprovalFacade;
import com.chomiii.planning.Plan;
import com.chomiii.tools.ToolDefinition;
import com.chomiii.tools.ToolExecutionContext;
import com.chomiii.tools.ToolRegistry;
import com.chomiii.tools.trace.ToolTraceCommand;
import com.chomiii.tools.trace.ToolTraceService;
import java.util.UUID;
import java.util.Map;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanRunner {
    private final PlanExecutionService plans;
    private final ToolRegistry tools;
    private final ToolTraceService traces;
    private final ApprovalFacade approvals;
    private final int maxSteps;

    public PlanRunner(PlanExecutionService plans, ToolRegistry tools, ToolTraceService traces,
            ApprovalFacade approvals, @Value("${chomiii.planning.max-segment-steps:12}") int maxSteps) {
        this.plans = plans;
        this.tools = tools;
        this.traces = traces;
        this.approvals = approvals;
        this.maxSteps = Math.max(1, Math.min(12, maxSteps));
    }

    @Transactional(timeout = 30)
    public PlanExecution run(String owner, UUID conversationId, UUID planId) {
        var plan = plans.prepareRun(owner, conversationId, planId);
        if (plan.state() == PlanExecution.State.CANCELLED || plan.state() == PlanExecution.State.COMPLETED
                || plan.steps().stream().anyMatch(s -> s.state() == PlanExecution.State.AWAITING_APPROVAL
                        || s.state() == PlanExecution.State.RUNNING)) return plan;
        UUID requestId = UUID.randomUUID();
        var context = new ToolExecutionContext(owner, conversationId, requestId, null, false, ignored -> {});
        for (int count = 0; count < maxSteps; count++) {
            PlanExecution snapshot = plan;
            Plan.Step ready = plan.proposal().steps().stream().filter(proposed -> {
                var state = step(snapshot, proposed.id()).state();
                return state == PlanExecution.State.PROPOSED && proposed.dependsOn().stream()
                        .allMatch(id -> step(snapshot, id).state() == PlanExecution.State.COMPLETED);
            }).findFirst().orElse(null);
            if (ready == null) break;
            var execution = step(plan, ready.id());
            var definition = tools.definition(ready.capability()).orElse(null);
            boolean read = "todo.list".equals(ready.capability());
            if (definition == null || !definition.name().equals(ready.capability())
                    || !definition.capability().equals(read ? "todo.read" : "todo.write")
                    || definition.effect() != (read ? ToolDefinition.Effect.READ : ToolDefinition.Effect.WRITE)
                    || tools.available(context).stream().noneMatch(definition::equals)) {
                plan = plans.blockUnavailable(owner, planId, plan.version(), ready.id());
                continue;
            }
            Map<String, Object> arguments = ready.arguments();
            if ("todo.complete".equals(ready.capability()) && arguments.containsKey("listStepId")) {
                var observation = step(plan, (String) arguments.get("listStepId")).observation();
                if (observation == null || observation.truncated()) {
                    plan = plans.blockUnavailable(owner, planId, plan.version(), ready.id());
                    continue;
                }
                String title = (String) arguments.get("title");
                var matches = observation.todos().stream().filter(t -> t.status().equals("open") && t.title().equals(title)).toList();
                if (matches.size() != 1) {
                    plan = plans.blockUnavailable(owner, planId, plan.version(), ready.id());
                    continue;
                }
                arguments = Map.of("id", matches.get(0).id());
            }
            var trace = traces.recordRequested(owner, new ToolTraceCommand.Requested(conversationId, requestId,
                    planId, execution.executionId(), definition.name(), definition.capability(), definition.effect(),
                    null, null, null, null, arguments)).invocationId();
            if (!read) {
                if (!approvals.validPlanProposal(context, definition, arguments)) {
                    traces.recordFinished(owner, trace, "failed", "INVALID_ACTION", java.util.Map.of());
                    plan = plans.blockUnavailable(owner, planId, plan.version(), ready.id());
                    continue;
                }
                var approval = approvals.proposeForPlan(context, definition, arguments, trace, planId, execution.executionId());
                plan = plans.awaitApproval(owner, planId, plan.version(), ready.id(), approval.id());
                break;
            }
            plan = plans.start(owner, planId, plan.version(), ready.id(), null);
            traces.recordAuthorized(owner, trace, null);
            traces.recordStarted(owner, trace);
            var result = tools.executeAuthorized(context, definition.name(), arguments);
            traces.recordFinished(owner, trace, "succeeded", null, result.output());
            plan = plans.completeFromObservation(owner, planId, plan.version(), ready.id(), trace,
                    normalize(result.output(), arguments));
        }
        return plan;
    }

    private static PlanExecution.Observation normalize(Map<String, Object> output, Map<String, Object> arguments) {
        if (!(output.get("todos") instanceof List<?> rows) || !(output.get("truncated") instanceof Boolean truncated)
                || rows.size() > 20) throw new IllegalStateException("Invalid todo list result");
        var todos = new java.util.ArrayList<PlanExecution.Todo>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> item) || !(item.get("id") instanceof String id)
                    || !(item.get("title") instanceof String title) || !(item.get("status") instanceof String status)
                    || title.isBlank() || title.length() > 200 || !List.of("open", "completed").contains(status))
                throw new IllegalStateException("Invalid todo list result");
            UUID.fromString(id);
            todos.add(new PlanExecution.Todo(id, title, status));
        }
        int limit = arguments.containsKey("limit") ? ((Number) arguments.get("limit")).intValue() : 10;
        return new PlanExecution.Observation(todos, truncated || rows.size() >= limit);
    }

    private static PlanExecution.Step step(PlanExecution plan, String id) {
        return plan.steps().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }
}
