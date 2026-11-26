package com.chomiii.tools.trace;

import java.time.Instant;
import java.util.UUID;

/** Persisted shape of one tool invocation trace. */
record ToolTraceRow(UUID id, String ownerHash, UUID conversationId, UUID requestId, UUID planId, UUID stepId,
        String toolName, String capability, String effect, String provider, String model,
        String authorization, UUID approvalId, String status, String errorCode,
        UUID retryOfId, String idempotencyKey, ToolTraceDto.Summary argumentSummary,
        ToolTraceDto.Summary resultSummary, Instant requestedAt, Instant startedAt,
        Instant finishedAt, Long durationMs) {}
