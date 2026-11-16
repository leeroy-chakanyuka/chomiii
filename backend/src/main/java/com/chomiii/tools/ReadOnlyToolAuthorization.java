package com.chomiii.tools;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Default-deny capability policy for the current read-only runtime. */
@Component
public final class ReadOnlyToolAuthorization implements ToolAuthorization {
    private static final Set<String> IDENTITY_ARGUMENTS = Set.of("owner", "ownerid", "ownerhash", "userid", "sessionid");

    @Override
    public boolean mayExpose(ToolExecutionContext context, ToolDefinition definition) {
        return permitted(context, definition);
    }

    @Override
    public Decision authorize(ToolExecutionContext context, ToolDefinition definition, Map<String, Object> arguments) {
        if (!permitted(context, definition) || arguments == null) return Decision.DENY;
        boolean choosesOwner = arguments.keySet().stream().anyMatch(key -> IDENTITY_ARGUMENTS.contains(
                key.replace("_", "").toLowerCase(Locale.ROOT)));
        return choosesOwner ? Decision.DENY : Decision.ALLOW;
    }

    private static boolean permitted(ToolExecutionContext context, ToolDefinition definition) {
        if (definition.effect() != ToolDefinition.Effect.READ) return false;
        return switch (definition.capability()) {
            case "memory.read" -> context.memoryRecallEnabled();
            default -> false;
        };
    }
}
