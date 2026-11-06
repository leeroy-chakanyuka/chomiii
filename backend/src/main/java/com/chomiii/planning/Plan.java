package com.chomiii.planning;

import java.util.List;
import java.util.UUID;

/** A proposal; its steps do not represent executed or authorized actions. */
public record Plan(UUID id, String goal, String summary, List<MemoryReference> memoryReferences,
        List<String> constraints, List<Clarification> missingInformation, List<Step> steps,
        boolean executed) {
    public Plan {
        if (executed) throw new IllegalArgumentException("Planning cannot mark actions executed");
        memoryReferences = List.copyOf(memoryReferences);
        constraints = List.copyOf(constraints);
        missingInformation = List.copyOf(missingInformation);
        steps = List.copyOf(steps);
    }

    public record MemoryReference(UUID id, String relevance) {}
    public record Clarification(String id, String question, String kind, List<String> blocks) {
        public Clarification { blocks = List.copyOf(blocks); }
    }
    public record Step(String id, String title, String capability, List<String> dependsOn,
            String approvalHint, String availability, String status) {
        public Step { dependsOn = List.copyOf(dependsOn); }
    }
}
