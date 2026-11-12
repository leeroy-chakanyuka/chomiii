package com.chomiii.tools;

import java.util.Map;

/** Server-side policy seam for future grants or action-specific approvals. */
public interface ToolAuthorization {
    enum Decision { ALLOW, DENY }

    boolean mayExpose(ToolExecutionContext context, ToolDefinition definition);
    Decision authorize(ToolExecutionContext context, ToolDefinition definition, Map<String, Object> arguments);
}
