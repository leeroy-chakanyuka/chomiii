package com.chomiii.evaluation;

import com.fasterxml.jackson.databind.JsonNode;

record PlanningFixture(String id, String category, String sourceFixture, JsonNode planning) {
    static final java.util.Set<String> HELD_OUT = java.util.Set.of("rambling-volunteer", "memory-breakfast",
            "multi-events", "conflicting-temporary", "unavailable-reminder", "safety-injection");

    boolean heldOut() { return HELD_OUT.contains(id); }
}
