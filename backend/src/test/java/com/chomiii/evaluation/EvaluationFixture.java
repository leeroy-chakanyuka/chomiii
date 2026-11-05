package com.chomiii.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

record EvaluationFixture(String id, int version, String description, String category,
        String difficulty, String importance, String inputType, Boolean synthetic, List<Turn> turns,
        List<ExpectedMemory> expectedMemories, List<ForbiddenMemory> forbiddenMemories,
        List<RetrievalCheck> retrievalChecks, List<LifecycleAction> lifecycle,
        JsonNode planning) {
    EvaluationFixture(String id, int version, String description, String category, String difficulty,
            String inputType, Boolean synthetic, List<Turn> turns, List<ExpectedMemory> expectedMemories,
            List<ForbiddenMemory> forbiddenMemories, List<RetrievalCheck> retrievalChecks,
            List<LifecycleAction> lifecycle, JsonNode planning) {
        this(id, version, description, category, difficulty, "medium", inputType, synthetic, turns,
                expectedMemories, forbiddenMemories, retrievalChecks, lifecycle, planning);
    }
    record Turn(String id, String role, String content, String expectedEligibility, JsonNode scriptedExtraction,
            Integer targetChars, String paddingText) {
        Turn(String id, String role, String content, String expectedEligibility, JsonNode scriptedExtraction) {
            this(id, role, content, expectedEligibility, scriptedExtraction, null, null);
        }
    }
    record ExpectedMemory(String id, String statement, List<String> aliases, List<String> sourceTurnIds,
            String kind, String importance, String expectedStatus, Boolean inScope, String origin) {
        ExpectedMemory {
            if (origin == null) origin = "conversation";
        }
        ExpectedMemory(String id, String statement, List<String> aliases, List<String> sourceTurnIds,
                String kind, String importance, String expectedStatus, Boolean inScope) {
            this(id, statement, aliases, sourceTurnIds, kind, importance, expectedStatus, inScope, "conversation");
        }
    }
    record ForbiddenMemory(String statement, String reason, String category) {}
    record RetrievalCheck(String query, List<String> expectedMemoryIds) {}
    record LifecycleAction(String afterTurnId, String operation, String memoryId, String content) {}
}
