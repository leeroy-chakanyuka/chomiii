package com.chomiii.tools;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.chomiii.approval.ApprovalService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.stereotype.Component;

/** Default-deny capability policy for the current read-only runtime. */
@Component
public final class ReadOnlyToolAuthorization implements ToolAuthorization {
    private static final Set<String> IDENTITY_ARGUMENTS = Set.of("owner", "ownerid", "ownerhash", "userid", "sessionid", "actionid", "approvalid", "approved");
    private final ApprovalService approvals;

    public ReadOnlyToolAuthorization() { this.approvals = null; }

    @Autowired
    public ReadOnlyToolAuthorization(ApprovalService approvals) { this.approvals = approvals; }

    @Override
    public boolean mayExpose(ToolExecutionContext context, ToolDefinition definition) {
        return permitted(context, definition);
    }

    @Override
    public Decision authorize(ToolExecutionContext context, ToolDefinition definition, Map<String, Object> arguments) {
        if (!permitted(context, definition) || arguments == null) return Decision.DENY;
        boolean choosesOwner = arguments.keySet().stream().anyMatch(key -> IDENTITY_ARGUMENTS.contains(
                key.replace("_", "").toLowerCase(Locale.ROOT)));
        if (choosesOwner) return Decision.DENY;
        if (definition.effect() == ToolDefinition.Effect.READ) return Decision.ALLOW;
        if (context.actionId() == null) return Decision.REQUIRE_APPROVAL;
        if (approvals == null || !TransactionSynchronizationManager.isActualTransactionActive()) return Decision.DENY;
        return approvals.isAuthorized(context.owner(), context.conversationId(), context.requestId(), context.actionId(),
                definition, arguments) ? Decision.ALLOW : Decision.DENY;
    }

    private static boolean permitted(ToolExecutionContext context, ToolDefinition definition) {
        if (definition.effect() == ToolDefinition.Effect.WRITE) {
            return "todo.write".equals(definition.capability())
                    && Set.of("todo.create", "todo.complete").contains(definition.name());
        }
        if (definition.effect() != ToolDefinition.Effect.READ) return false;
        return switch (definition.capability()) {
            case "memory.read" -> context.memoryRecallEnabled();
            case "todo.read" -> "todo.list".equals(definition.name());
            default -> false;
        };
    }
}
