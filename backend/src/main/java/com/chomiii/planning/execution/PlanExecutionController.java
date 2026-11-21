package com.chomiii.planning.execution;

import com.chomiii.session.AnonymousSessionFilter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conversations/{conversationId}/plans")
public class PlanExecutionController {
    private final PlanExecutionService plans;
    private final PlanRunner runner;

    public PlanExecutionController(PlanExecutionService plans, PlanRunner runner) { this.plans = plans; this.runner = runner; }

    public record StepView(String id, String title, PlanExecution.State state, List<String> dependsOn,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) PlanExecution.Observation observation) {}
    public record PlanView(UUID id, String goal, String summary, PlanExecution.State state, long version,
            List<StepView> steps, Instant updatedAt) {}

    @GetMapping
    public List<PlanView> list(@RequestAttribute(AnonymousSessionFilter.OWNER_ATTRIBUTE) String owner,
            @PathVariable UUID conversationId) {
        return plans.list(owner, conversationId).stream().map(PlanExecutionController::view).toList();
    }

    @PostMapping("/{planId}/cancel")
    public PlanView cancel(@RequestAttribute(AnonymousSessionFilter.OWNER_ATTRIBUTE) String owner,
            @PathVariable UUID conversationId, @PathVariable UUID planId) {
        return view(plans.cancelForConversation(owner, conversationId, planId));
    }

    @PostMapping("/{planId}/run")
    public PlanView run(@RequestAttribute(AnonymousSessionFilter.OWNER_ATTRIBUTE) String owner,
            @PathVariable UUID conversationId, @PathVariable UUID planId) {
        return view(runner.run(owner, conversationId, planId));
    }

    private static PlanView view(PlanExecution execution) {
        var proposal = execution.proposal();
        var steps = proposal.steps().stream().map(step -> {
            var saved = execution.steps().stream().filter(s -> s.id().equals(step.id())).findFirst().orElseThrow();
            return new StepView(step.id(), step.title(), saved.state(), step.dependsOn(), saved.observation());
        }).toList();
        return new PlanView(proposal.id(), proposal.goal(), proposal.summary(), execution.state(), execution.version(),
                steps, execution.updatedAt());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Void> notFound() { return ResponseEntity.status(HttpStatus.NOT_FOUND).build(); }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Void> conflict() { return ResponseEntity.status(HttpStatus.CONFLICT).build(); }
}
