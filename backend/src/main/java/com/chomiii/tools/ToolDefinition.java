package com.chomiii.tools;

import java.util.Map;
import java.util.Objects;

/**
 * Provider-neutral tool contract. Model SDKs map this data into their native
 * function declarations; they must never decide permissions or execute a tool.
 */
public record ToolDefinition(String name, String description, Map<String, Object> parameters,
        String capability, Effect effect) {
    public enum Effect { READ, WRITE }

    public ToolDefinition {
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("Invalid tool name");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Tool description is required");
        }
        if (capability == null || !capability.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("Invalid tool capability");
        }
        parameters = ToolValues.copy(Objects.requireNonNull(parameters));
        Objects.requireNonNull(effect);
    }
}
