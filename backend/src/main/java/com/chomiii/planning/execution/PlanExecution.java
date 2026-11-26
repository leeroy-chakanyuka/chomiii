package com.chomiii.planning.execution;

import com.chomiii.planning.Plan;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PlanExecution(String owner, UUID conversationId, Plan proposal, State state, long version,
        Instant createdAt, Instant updatedAt, List<Step> steps) {
    public PlanExecution { steps = List.copyOf(steps); }

    public enum State { PROPOSED, AWAITING_APPROVAL, RUNNING, BLOCKED, COMPLETED, FAILED, CANCELLED }

    public record Todo(String id, String title, String status) {}
    public record Observation(List<Todo> todos, boolean truncated) {
        public Observation { todos = List.copyOf(todos); }
    }

    public record Step(String id, UUID executionId, String capability, State state, String argumentsRef,
            UUID observationRef, UUID pendingApprovalId, Observation observation) {
        public Step(String id, UUID executionId, String capability, State state, String argumentsRef,
                UUID observationRef, UUID pendingApprovalId) {
            this(id, executionId, capability, state, argumentsRef, observationRef, pendingApprovalId, null);
        }
    }
}
