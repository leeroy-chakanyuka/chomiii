package com.chomiii.tools;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Registry of the tools made available to a turn. */
public final class ToolRegistry {
    public record Registered(ToolDefinition definition, InternalTool tool) {}
    private final Map<String, Registered> tools;

    public ToolRegistry(List<InternalTool> tools) {
        Map<String, Registered> registered = new LinkedHashMap<>();
        for (InternalTool tool : List.copyOf(tools)) {
            ToolDefinition definition = tool.definition();
            ToolSchemaValidator.validateSchema(definition.parameters());
            if (registered.putIfAbsent(definition.name(), new Registered(definition, tool)) != null) {
                throw new IllegalArgumentException("Duplicate tool name: " + definition.name());
            }
        }
        this.tools = Collections.unmodifiableMap(registered);
    }

    public List<ToolDefinition> definitions() {
        return tools.values().stream().map(Registered::definition).toList();
    }

    public Optional<Registered> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }
}
