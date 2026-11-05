package com.chomiii.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

record EvaluationFixture(String id, int version, String description, String category,
        String difficulty, String inputType, Boolean synthetic, List<Turn> turns,
        List<ExpectedMemory> expectedMemories, List<ForbiddenMemory> forbiddenMemories,
        List<RetrievalCheck> retrievalChecks, List<LifecycleAction> lifecycle,
        JsonNode planning) {
    record Turn(String id, String role, String content, String expectedEligibility, JsonNode scriptedExtraction) {}
    record ExpectedMemory(String id, String statement, List<String> aliases, List<String> sourceTurnIds,
            String kind, String importance, String expectedStatus, Boolean inScope) {}
    record ForbiddenMemory(String statement, String reason, String category) {}
    record RetrievalCheck(String query, List<String> expectedMemoryIds) {}
    record LifecycleAction(String afterTurnId, String operation, String memoryId, String content) {}
}
