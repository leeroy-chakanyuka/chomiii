package com.chomiii.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validation only: the M3.1 planner and tool catalog do not exist yet. */
final class PlanningContract {
    static void validate(JsonNode planning, Set<String> factIds) {
        if (planning == null || !planning.isObject()) throw new IllegalArgumentException("Planning expectations must be an object");
        text(planning, "instruction");
        List<String> memoryIds = strings(planning, "oracleMemoryIds");
        if (!factIds.containsAll(memoryIds)) throw new IllegalArgumentException("Planning references unknown memory");
        if (strings(planning, "requiredGoals").isEmpty()) throw new IllegalArgumentException("Planning needs a goal");
        Set<String> tools = Set.copyOf(strings(planning, "toolsAvailable"));
        strings(planning, "missingInformation");
        strings(planning, "forbiddenActions");
        JsonNode steps = planning.path("requiredSteps");
        if (!steps.isArray()) throw new IllegalArgumentException("Planning needs requiredSteps");
        Map<String, List<String>> dependencies = new HashMap<>();
        for (JsonNode step : steps) {
            String id = text(step, "id");
            String operation = text(step, "operation");
            if (!tools.contains(operation)) throw new IllegalArgumentException("Planning step uses an unavailable tool");
            if (!step.path("requiresApproval").isBoolean()) throw new IllegalArgumentException("Approval expectation is required");
            if (dependencies.putIfAbsent(id, strings(step, "dependsOn")) != null) {
                throw new IllegalArgumentException("Duplicate planning step");
            }
        }
        for (List<String> values : dependencies.values()) {
            if (!dependencies.keySet().containsAll(values)) throw new IllegalArgumentException("Unknown planning dependency");
        }
        var visiting = new HashSet<String>();
        var done = new HashSet<String>();
        for (String id : dependencies.keySet()) visit(id, dependencies, visiting, done);
    }

    private static void visit(String id, Map<String, List<String>> dependencies, Set<String> visiting, Set<String> done) {
        if (done.contains(id)) return;
        if (!visiting.add(id)) throw new IllegalArgumentException("Planning dependencies contain a cycle");
        for (String dependency : dependencies.get(id)) visit(dependency, dependencies, visiting, done);
        visiting.remove(id);
        done.add(id);
    }

    private static List<String> strings(JsonNode node, String field) {
        JsonNode values = node.path(field);
        if (!values.isArray()) throw new IllegalArgumentException("Planning needs " + field);
        var result = new java.util.ArrayList<String>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException("Invalid " + field);
            result.add(value.asText());
        }
        return List.copyOf(result);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException("Planning needs " + field);
        return value.asText();
    }
}
