package com.chomiii.tools;

import java.util.Map;

/**
 * A model-provider-neutral tool description. The Java application owns
 * exposure, parameter validation, permissions and execution; an inference
 * adapter only converts this descriptor into provider-specific syntax.
 */
public record ToolDescriptor(String name, String description,
        Map<String, Object> parameters, boolean readOnly) {
    public ToolDescriptor {
        if (name == null || !name.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,79}")
                || description == null || description.isBlank()
                || parameters == null || parameters.isEmpty()) {
            throw new IllegalArgumentException("Invalid tool descriptor");
        }
        parameters = Map.copyOf(parameters);
    }
}
