package com.chomiii.tools.trace;

import com.chomiii.chat.ConversationNotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner-scoped lifecycle for tool invocation traces. The execution wiring
 * records the request, the authorization/start decision, and one terminal
 * outcome; the conversation endpoint exposes the rows to the owning session.
 *
 * Requests can be denied, fail or be cancelled before execution starts.
 * Summaries store counts, not argument or result values.
 */
@Service
public class ToolTraceService {
    private static final String NAME_PATTERN = "[A-Za-z][A-Za-z0-9_.-]{0,63}";
    private static final String LABEL_ALPHABET = "A-Za-z0-9._-";
    private static final String ERROR_CODE_PATTERN = "[A-Z][A-Z0-9_]{0,47}";

    private final ToolTraceRepository repository;

    public ToolTraceService(ToolTraceRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ToolTraceDto.Trace recordRequested(String owner, ToolTraceCommand.Requested command) {
        requireOwner(owner);
        if (command.conversationId() == null || command.requestId() == null) {
            throw new IllegalArgumentException("Conversation and request ids are required");
        }
        if (command.toolName() == null || command.capability() == null) {
            throw new IllegalArgumentException("Tool name and capability are required");
        }
        if (!command.toolName().matches(NAME_PATTERN) || !command.capability().matches(NAME_PATTERN)) {
            throw new IllegalArgumentException("Invalid tool metadata");
        }
        requireLabel("provider", command.provider(), 64);
        requireLabel("model", command.model(), 120);
        if (!repository.conversationExists(command.conversationId(), owner)) {
            throw new ConversationNotFoundException();
        }
        var now = Instant.now();
        var row = new ToolTraceRow(UUID.randomUUID(), owner, command.conversationId(), command.requestId(),
                command.planId(), command.stepId(), command.toolName(), command.capability(),
                command.effect() == null ? null : command.effect().name(), command.provider(), command.model(), null, null,
                "requested", null, command.retryOfId(), digest(command.idempotencyKey()),
                ToolTraceSummaries.from(command.arguments()), new ToolTraceDto.Summary(0, List.of()),
                now, null, null, null);
        repository.insert(row);
        return toDto(row);
    }

    @Transactional
    public void recordAuthorized(String owner, UUID invocationId, UUID approvalId) {
        if (!repository.markAuthorized(invocationId, owner, approvalId)) {
            throw new IllegalStateException("Tool trace left its requested state before authorization");
        }
    }

    @Transactional
    public ToolTraceDto.Trace markAwaitingApproval(String owner, UUID traceId, UUID conversationId, UUID requestId,
            String toolName, String capability, String effect, UUID approvalId) {
        requireOwner(owner);
        java.util.Objects.requireNonNull(approvalId);
        if (!repository.markAwaitingApproval(traceId, owner, conversationId, requestId, toolName, capability, effect, approvalId)) {
            throw new IllegalStateException("Tool trace is not awaiting a decision");
        }
        return toDto(stored(owner, traceId));
    }

    @Transactional(readOnly = true)
    public Optional<UUID> pendingForApproval(String owner, UUID conversationId, UUID approvalId) {
        requireOwner(owner);
        return repository.pendingForApproval(owner, java.util.Objects.requireNonNull(conversationId),
                java.util.Objects.requireNonNull(approvalId));
    }

    @Transactional
    public Instant recordStarted(String owner, UUID invocationId) {
        Instant now = Instant.now();
        if (!repository.markStarted(invocationId, owner, now)) {
            throw new IllegalStateException("Tool trace was not authorized or has already started");
        }
        return now;
    }

    @Transactional
    public ToolTraceDto.Trace recordFinished(String owner, UUID invocationId, String status,
            String errorCode, Map<String, ?> result) {
        requireOwner(owner);
        if (!List.of("succeeded", "denied", "failed", "cancelled", "timeout").contains(status)) {
            throw new IllegalArgumentException("Invalid tool outcome");
        }
        if (!"succeeded".equals(status)) requireErrorCode(errorCode);
        var row = stored(owner, invocationId);
        var now = Instant.now();
        Instant start = row.startedAt() == null ? row.requestedAt() : row.startedAt();
        long duration = Math.max(0, Duration.between(start, now).toMillis());
        if (!repository.markTerminal(invocationId, owner, status, errorCode,
                ToolTraceSummaries.toJson(ToolTraceSummaries.from(result)), now, duration, "denied".equals(status))) {
            throw new IllegalStateException("Tool trace was already finished");
        }
        return toDto(stored(owner, invocationId));
    }

    @Transactional(readOnly = true)
    public List<ToolTraceDto.Trace> list(UUID conversationId, String owner) {
        requireOwner(owner);
        if (conversationId == null) {
            throw new IllegalArgumentException("Conversation id is required");
        }
        if (!repository.conversationExists(conversationId, owner)) {
            throw new ConversationNotFoundException();
        }
        return repository.listForConversation(conversationId, owner).stream().map(this::toDto).toList();
    }

    private ToolTraceRow stored(String owner, UUID invocationId) {
        return repository.find(invocationId, owner).orElseThrow(ToolTraceNotFoundException::new);
    }

    private static void requireOwner(String owner) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("Owner is required");
        }
    }

    private static void requireLabel(String field, String value, int maxLength) {
        if (value == null) {
            return;
        }
        if (value.length() > maxLength || !value.matches("[" + LABEL_ALPHABET + "]{1," + maxLength + "}")) {
            throw new IllegalArgumentException("Invalid " + field);
        }
    }

    private static void requireErrorCode(String errorCode) {
        if (errorCode == null || !errorCode.matches(ERROR_CODE_PATTERN)) {
            throw new IllegalArgumentException("A structured error code is required");
        }
    }

    private static String digest(String value) {
        if (value == null) return null;
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private ToolTraceDto.Trace toDto(ToolTraceRow row) {
        return new ToolTraceDto.Trace(row.id(), row.conversationId(), row.requestId(), row.planId(), row.stepId(),
                row.toolName(), row.capability(), row.effect(), row.provider(), row.model(),
                row.authorization(), row.approvalId(), "requested".equals(row.status()) && row.approvalId() != null
                        && row.authorization() == null ? "awaiting_approval" : row.status(), row.errorCode(),
                row.retryOfId(), row.idempotencyKey(), row.argumentSummary(), row.resultSummary(),
                row.requestedAt(), row.startedAt(), row.finishedAt(), row.durationMs());
    }
}
