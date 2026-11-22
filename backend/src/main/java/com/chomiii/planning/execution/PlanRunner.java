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
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PlanRunner {
    private final PlanExecutionService plans;
    private final ToolRegistry tools;
    private final ToolTraceService traces;
    private final ApprovalFacade approvals;
    private final int maxSteps;
    private final PlatformTransactionManager transactions;

    public PlanRunner(PlanExecutionService plans, ToolRegistry tools, ToolTraceService traces,
            ApprovalFacade approvals, PlatformTransactionManager transactions,
            @Value("${chomiii.planning.max-segment-steps:12}") int maxSteps) {
        this.plans = plans;
        this.tools = tools;
        this.traces = traces;
        this.approvals = approvals;
        this.transactions = transactions;
        this.maxSteps = Math.max(1, Math.min(12, maxSteps));
    }

    public PlanExecution run(String owner, UUID conversationId, UUID planId) {
        var context = new ToolExecutionContext(owner, conversationId, UUID.randomUUID(), null, false, ignored -> {});
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        var tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Advance last = null;
        for (int count = 0; count < maxSteps; count++) {
            long remaining = deadline - System.nanoTime();
            if (last != null && remaining <= 0) break;
            tx.setTimeout(Math.max(1, (int) Math.ceilDiv(remaining, TimeUnit.SECONDS.toNanos(1))));
            try {
                last = tx.execute(status -> advance(context, planId));
            } catch (FailedRead failure) {
                // A transactional tool may have poisoned its transaction. Re-lock after rollback,
                // and never overwrite a concurrent retry, cancellation, or revision.
                last = tx.execute(status -> recordFailure(owner, failure));
            }
            if (!last.more()) break;
        }
        return last.plan();
    }

    private record Advance(PlanExecution plan, boolean more) {}

    private Advance advance(ToolExecutionContext context, UUID planId) {
        String owner = context.owner();
        var plan = plans.prepareRun(owner, context.conversationId(), planId);
        if (plan.state() == PlanExecution.State.CANCELLED || plan.state() == PlanExecution.State.COMPLETED
                || plan.steps().stream().anyMatch(s -> s.state() == PlanExecution.State.AWAITING_APPROVAL
                        || s.state() == PlanExecution.State.RUNNING)) return new Advance(plan, false);
        Plan.Step ready = plan.proposal().steps().stream().filter(proposed ->
                step(plan, proposed.id()).state() == PlanExecution.State.PROPOSED && proposed.dependsOn().stream()
                        .allMatch(id -> step(plan, id).state() == PlanExecution.State.COMPLETED)).findFirst().orElse(null);
        if (ready == null) return new Advance(plan, false);
        var execution = step(plan, ready.id());
        var definition = tools.definition(ready.capability()).orElse(null);
        boolean read = "todo.list".equals(ready.capability());
        if (definition == null || !definition.name().equals(ready.capability())
                || !definition.capability().equals(read ? "todo.read" : "todo.write")
                || definition.effect() != (read ? ToolDefinition.Effect.READ : ToolDefinition.Effect.WRITE)
                || tools.available(context).stream().noneMatch(definition::equals)) {
            return new Advance(plans.blockUnavailable(owner, planId, plan.version(), ready.id()), true);
        }
        Map<String, Object> arguments = ready.arguments();
        if ("todo.complete".equals(ready.capability()) && arguments.containsKey("listStepId")) {
            var observation = step(plan, (String) arguments.get("listStepId")).observation();
            if (observation == null || observation.truncated()) {
                return new Advance(plans.blockUnavailable(owner, planId, plan.version(), ready.id()), true);
            }
            String title = (String) arguments.get("title");
            var matches = observation.todos().stream().filter(t -> t.status().equals("open") && t.title().equals(title)).toList();
            if (matches.size() != 1) return new Advance(plans.blockUnavailable(owner, planId, plan.version(), ready.id()), true);
            arguments = Map.of("id", matches.get(0).id());
        }
        var command = new ToolTraceCommand.Requested(context.conversationId(), context.requestId(),
                planId, execution.executionId(), definition.name(), definition.capability(), definition.effect(),
                null, null, null, null, arguments);
        var trace = traces.recordRequested(owner, command).invocationId();
        if (!read) {
            if (!approvals.validPlanProposal(context, definition, arguments)) {
                traces.recordFinished(owner, trace, "failed", "INVALID_ACTION", Map.of());
                return new Advance(plans.blockUnavailable(owner, planId, plan.version(), ready.id()), true);
            }
            var approval = approvals.proposeForPlan(context, definition, arguments, trace, planId, execution.executionId());
            return new Advance(plans.awaitApproval(owner, planId, plan.version(), ready.id(), approval.id()), false);
        }
        var running = plans.start(owner, planId, plan.version(), ready.id(), null);
        traces.recordAuthorized(owner, trace, null);
        traces.recordStarted(owner, trace);
        Map<String, Object> output;
        PlanExecution.Observation observation;
        try {
            output = tools.executeAuthorized(context, definition.name(), arguments).output();
            observation = normalize(output);
        } catch (DataAccessException | TransactionException | SecurityException unavailable) {
            throw unavailable;
        } catch (RuntimeException toolFailure) {
            throw new FailedRead(ready.id(), plan.version(), command, toolFailure);
        }
        traces.recordFinished(owner, trace, "succeeded", null, output);
        return new Advance(plans.completeFromObservation(owner, planId, running.version(), ready.id(), trace, observation), true);
    }

    private static final class FailedRead extends RuntimeException {
        final String stepId;
        final long version;
        final ToolTraceCommand.Requested command;

        FailedRead(String stepId, long version, ToolTraceCommand.Requested command, RuntimeException cause) {
            super("Plan read failed", cause);
            this.stepId = stepId;
            this.version = version;
            this.command = command;
        }
    }

    private Advance recordFailure(String owner, FailedRead failure) {
        var command = failure.command;
        var plan = plans.prepareRun(owner, command.conversationId(), command.planId());
        var attempted = step(plan, failure.stepId);
        if (plan.version() != failure.version || attempted.state() != PlanExecution.State.PROPOSED
                || !attempted.executionId().equals(command.stepId())) return new Advance(plan, false);
        var trace = traces.recordRequested(owner, command).invocationId();
        traces.recordFinished(owner, trace, "failed", "READ_FAILED", Map.of());
        var running = plans.start(owner, command.planId(), plan.version(), failure.stepId, null);
        return new Advance(plans.fail(owner, command.planId(), running.version(), failure.stepId), true);
    }

    private static PlanExecution.Observation normalize(Map<String, Object> output) {
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
        return new PlanExecution.Observation(todos, truncated);
    }

    private static PlanExecution.Step step(PlanExecution plan, String id) {
        return plan.steps().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }
}
