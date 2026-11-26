package com.chomiii.tools.trace;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** HTTP shapes for tool invocation traces. */
public final class ToolTraceDto {
    private ToolTraceDto() {}

    /** Counts only; labels are reserved for future server-defined metadata. */
    public record Summary(int count, List<String> labels) {}

    public record Trace(UUID invocationId, UUID conversationId, UUID requestId, UUID planId, UUID stepId,
            String toolName, String capability, String effect, String provider, String model,
            String authorization, UUID approvalId, String status, String errorCode,
            UUID retryOfId, String idempotencyKey,
            Summary argumentSummary, Summary resultSummary,
            Instant requestedAt, Instant startedAt, Instant finishedAt, Long durationMs) {
        public Trace started(Instant at) {
            return new Trace(invocationId, conversationId, requestId, planId, stepId, toolName, capability, effect,
                    provider, model, "ALLOW", approvalId, "running", null, retryOfId, idempotencyKey,
                    argumentSummary, resultSummary, requestedAt, at, null, null);
        }
    }
}
