package com.chomiii.tools.trace;

import com.chomiii.tools.ToolDefinition;
import java.util.Map;
import java.util.UUID;

/** Recording commands sent by the execution wiring when tools run. */
public final class ToolTraceCommand {
    private ToolTraceCommand() {}

    /**
     * @param arguments only the number of top-level arguments is stored
     */
    public record Requested(UUID conversationId, UUID requestId, UUID planId, UUID stepId,
            String toolName, String capability, ToolDefinition.Effect effect,
            String provider, String model, UUID retryOfId, String idempotencyKey,
            Map<String, ?> arguments) {}
}
