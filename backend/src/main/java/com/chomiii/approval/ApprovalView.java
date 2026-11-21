package com.chomiii.approval;

import java.time.Instant;
import java.util.UUID;

public record ApprovalView(UUID id, UUID requestId, String toolName, String preview, Instant expiresAt,
        String status, String result, String error) {}
