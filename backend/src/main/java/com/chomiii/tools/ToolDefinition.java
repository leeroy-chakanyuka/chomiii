package com.chomiii.tools;

import java.util.Map;
import java.util.Objects;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Collections;

/**
 * Provider-neutral tool contract. Model SDKs map this data into their native
 * function declarations; they must never decide permissions or execute a tool.
 */
public record ToolDefinition(String name, String description, Map<String, Object> parameters, Effect effect) {
    public enum Effect { READ, WRITE }

    public ToolDefinition {
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("Invalid tool name");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Tool description is required");
        }
        parameters = freezeMap(Objects.requireNonNull(parameters));
        Objects.requireNonNull(effect);
    }

    private static Map<String, Object> freezeMap(Map<String, Object> input) {
        Map<String, Object> copy = new LinkedHashMap<>();
        input.forEach((key, value) -> copy.put(Objects.requireNonNull(key), freeze(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object freeze(Object value) {
        Objects.requireNonNull(value);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (!(key instanceof String name)) throw new IllegalArgumentException("Invalid schema key");
                copy.put(name, freeze(item));
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) return list.stream().map(ToolDefinition::freeze).toList();
        if (value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        throw new IllegalArgumentException("Invalid schema value");
    }
}
