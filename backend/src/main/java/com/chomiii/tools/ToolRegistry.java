package com.chomiii.tools;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Registry of the tools made available to a turn. */
@Component
public final class ToolRegistry {
    record Registered(ToolDefinition definition, InternalTool tool) {}
    private final Map<String, Registered> tools;
    private final ToolAuthorization authorization;

    public ToolRegistry(List<InternalTool> tools) {
        this(tools, new ReadOnlyToolAuthorization());
    }

    @Autowired
    public ToolRegistry(List<InternalTool> tools, ToolAuthorization authorization) {
        Map<String, Registered> registered = new LinkedHashMap<>();
        for (InternalTool tool : List.copyOf(tools)) {
            ToolDefinition definition = tool.definition();
            ToolSchemaValidator.validateSchema(definition.parameters());
            if (registered.putIfAbsent(definition.name(), new Registered(definition, tool)) != null) {
                throw new IllegalArgumentException("Duplicate tool name: " + definition.name());
            }
        }
        this.tools = Collections.unmodifiableMap(registered);
        this.authorization = java.util.Objects.requireNonNull(authorization);
    }

    public List<ToolDefinition> definitions() {
        return tools.values().stream().map(Registered::definition).toList();
    }

    public List<ToolDefinition> available(ToolExecutionContext context) {
        return tools.values().stream().filter(registered -> mayExpose(registered, context) && isAvailable(registered, context))
                .map(Registered::definition).toList();
    }

    Optional<Registered> findAvailable(String name, ToolExecutionContext context) {
        return find(name).filter(registered -> isAvailable(registered, context));
    }

    boolean mayExpose(Registered registered, ToolExecutionContext context) {
        try {
            return authorization.mayExpose(context, registered.definition());
        } catch (RuntimeException denied) {
            return false;
        }
    }

    boolean authorize(Registered registered, ToolExecutionContext context, Map<String, Object> arguments) {
        try {
            return authorization.authorize(context, registered.definition(), arguments) == ToolAuthorization.Decision.ALLOW;
        } catch (RuntimeException denied) {
            return false;
        }
    }

    Optional<Registered> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    private static boolean isAvailable(Registered registered, ToolExecutionContext context) {
        try {
            return registered.tool().available(context);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
