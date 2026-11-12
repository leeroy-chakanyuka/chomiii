package com.chomiii.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reviewable allowlist of known tools for one turn. Only read-only
 * capabilities are exposed until M3.2 supplies an approval policy.
 * The model cannot install or authorize additional tools.
 */
public final class ToolRegistry {
    private final Map<String, InternalTool> allowed;

    public ToolRegistry(List<InternalTool> tools) {
        Map<String, InternalTool> entries = new LinkedHashMap<>();
        for (InternalTool tool : List.copyOf(tools)) {
            String name = tool.definition().name();
            if (entries.putIfAbsent(name, tool) != null) {
                throw new IllegalArgumentException("Duplicate tool: " + name);
            }
        }
        allowed = Map.copyOf(entries);
    }

    /** Only capabilities that can execute without a write approval. */
    public List<ToolDescriptor> definitions() {
        return allowed.values().stream().map(InternalTool::definition)
                .filter(ToolDescriptor::readOnly).sorted(java.util.Comparator.comparing(ToolDescriptor::name)).toList();
    }

    /** Write tools are hard-denied until exact-action approval is implemented. */
    public InternalTool readOnly(String name) {
        InternalTool candidate = allowed.get(name);
        return candidate != null && candidate.definition().readOnly() ? candidate : null;
    }
}
