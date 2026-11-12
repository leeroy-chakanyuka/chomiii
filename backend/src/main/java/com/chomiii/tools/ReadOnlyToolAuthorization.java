package com.chomiii.tools;

import java.util.Map;
import org.springframework.stereotype.Component;

/** No write grants or approval mechanism exist yet. */
@Component
public final class ReadOnlyToolAuthorization implements ToolAuthorization {
    @Override
    public boolean mayExpose(ToolExecutionContext context, ToolDefinition definition) {
        return definition.effect() == ToolDefinition.Effect.READ
                && (!"memory.read".equals(definition.capability()) || context.memoryRecallEnabled());
    }

    @Override
    public Decision authorize(ToolExecutionContext context, ToolDefinition definition, Map<String, Object> arguments) {
        return mayExpose(context, definition) ? Decision.ALLOW : Decision.DENY;
    }
}
