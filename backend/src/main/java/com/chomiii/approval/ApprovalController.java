package com.chomiii.approval;

import com.chomiii.planning.execution.PlanRunner;
import com.chomiii.session.AnonymousSessionFilter;
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
@RequestMapping("/api/conversations/{conversationId}/approvals")
public class ApprovalController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApprovalController.class);
    private final ApprovalFacade approvals;
    private final PlanRunner runner;

    public ApprovalController(ApprovalFacade approvals, PlanRunner runner) {
        this.approvals = approvals;
        this.runner = runner;
    }

    @GetMapping
    public List<ApprovalView> list(@RequestAttribute(AnonymousSessionFilter.OWNER_ATTRIBUTE) String owner,
            @PathVariable UUID conversationId) {
        return approvals.list(owner, conversationId);
    }

    @PostMapping("/{approvalId}/approve")
    public ApprovalView approve(@RequestAttribute(AnonymousSessionFilter.OWNER_ATTRIBUTE) String owner,
            @PathVariable UUID conversationId, @PathVariable UUID approvalId) {
        var decision = approvals.approveAndPlanToResume(owner, conversationId, approvalId);
        if (decision.planToResume() != null) {
            try {
                runner.run(owner, conversationId, decision.planToResume());
            } catch (RuntimeException unavailable) {
                // The write is committed. A later Run can resume the plan.
                log.warn("Plan continuation unavailable after approval");
            }
        }
        return decision.view();
    }

    @PostMapping("/{approvalId}/reject")
    public ApprovalView reject(@RequestAttribute(AnonymousSessionFilter.OWNER_ATTRIBUTE) String owner,
            @PathVariable UUID conversationId, @PathVariable UUID approvalId) {
        return approvals.reject(owner, conversationId, approvalId);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Void> notFound() { return ResponseEntity.status(HttpStatus.NOT_FOUND).build(); }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Void> conflict() { return ResponseEntity.status(HttpStatus.CONFLICT).build(); }
}
