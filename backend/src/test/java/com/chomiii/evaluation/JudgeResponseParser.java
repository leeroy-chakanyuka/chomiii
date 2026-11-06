package com.chomiii.evaluation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class JudgeResponseParser {
    private final ObjectMapper json = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    JudgeAdapter.Judgment parse(String response, EvaluationFixture fixture,
            List<MemoryEvaluationRunner.StoredMemory> actual,
            List<MemoryEvaluationRunner.RetrievalObservation> retrievals, String model, String family) throws Exception {
        if (response == null || response.length() > 16_384) throw new IllegalArgumentException("Invalid judge response size");
        JsonNode root = json.readTree(response);
        if (root == null || !root.isObject() || !fixture.id().equals(text(root, "fixtureId"))) {
            throw new IllegalArgumentException("Judge fixture id does not match");
        }
        Map<String, EvaluationFixture.ExpectedMemory> expected = fixture.expectedMemories().stream()
                .collect(java.util.stream.Collectors.toMap(EvaluationFixture.ExpectedMemory::id, item -> item));
        Set<String> actualIds = actual.stream().map(MemoryEvaluationRunner.StoredMemory::id)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> usedExpected = new HashSet<>();
        Set<String> usedActual = new HashSet<>();
        var matches = new ArrayList<JudgeAdapter.Match>();
        for (JsonNode item : array(root, "matches")) {
            String expectedId = text(item, "expectedId");
            String actualId = text(item, "actualId");
            String verdict = text(item, "verdict");
            String evidence = text(item, "evidence");
            String reason = text(item, "reason");
            if (evidence.strip().length() < 8) throw new IllegalArgumentException("Judge evidence is too short");
            var fact = expected.get(expectedId);
            if (fact == null || !actualIds.contains(actualId) || !"supported".equals(verdict)
                    || !usedExpected.add(expectedId) || !usedActual.add(actualId)) {
                throw new IllegalArgumentException("Invalid or duplicate judge match");
            }
            boolean sourceEvidence = fixture.turns().stream().anyMatch(turn -> fact.sourceTurnIds().contains(turn.id())
                    && "user".equals(turn.role()) && turn.content().contains(evidence));
            if (!sourceEvidence) {
                sourceEvidence = actual.stream().filter(memory -> memory.id().equals(actualId)
                                && "user_explicit".equals(memory.source()))
                        .anyMatch(memory -> fixture.lifecycle().stream().anyMatch(action -> "edit".equals(action.operation())
                                && action.content() != null && action.content().equals(memory.content())
                                && action.content().contains(evidence)));
            }
            if (!sourceEvidence) throw new IllegalArgumentException("Judge evidence is not in the cited user turns");
            matches.add(new JudgeAdapter.Match(expectedId, actualId, evidence, reason));
        }
        var issues = new ArrayList<JudgeAdapter.Issue>();
        for (String name : List.of("unsupported", "contradictions", "wrongPersonAttributions", "overInferences")) {
            for (JsonNode item : array(root, name)) {
                String actualId = text(item, "actualId");
                if (!actualIds.contains(actualId) || !usedActual.add(actualId)) {
                    throw new IllegalArgumentException("Invalid or duplicate judge issue");
                }
                issues.add(new JudgeAdapter.Issue(actualId, name, text(item, "reason")));
            }
        }
        var missing = new ArrayList<String>();
        for (JsonNode item : array(root, "missingExpected")) {
            if (!item.isTextual() || !expected.containsKey(item.asText()) || usedExpected.contains(item.asText())
                    || missing.contains(item.asText())) throw new IllegalArgumentException("Invalid missing fact id");
            missing.add(item.asText());
        }
        var review = new ArrayList<String>();
        for (JsonNode item : array(root, "needsHumanReview")) {
            if (!item.isTextual() || !actualIds.contains(item.asText()) || usedActual.contains(item.asText())
                    || review.contains(item.asText())) throw new IllegalArgumentException("Invalid review memory id");
            review.add(item.asText());
        }
        var answerVerdicts = new ArrayList<JudgeAdapter.AnswerVerdict>();
        Set<String> answered = new HashSet<>();
        for (JsonNode item : array(root, "answers")) {
            String id = text(item, "retrievalId");
            String verdict = text(item, "verdict");
            String reason = text(item, "reason");
            String evidence = item.path("evidence").isTextual() ? item.path("evidence").asText() : "";
            var retrieval = retrievals.stream().filter(value -> value.id().equals(id)).findFirst();
            if (retrieval.isEmpty() || !answered.add(id)
                    || !Set.of("correct", "incorrect", "needs_review").contains(verdict)
                    || retrieval.get().answer() == null) throw new IllegalArgumentException("Invalid judge answer verdict");
            if ("correct".equals(verdict) && !retrieval.get().expectedMemoryIds().isEmpty()) {
                boolean supported = !evidence.isBlank() && actual.stream()
                        .filter(memory -> retrieval.get().selectedMemoryIds().contains(memory.id()))
                        .anyMatch(memory -> memory.content().contains(evidence));
                if (!supported) throw new IllegalArgumentException("Answer evidence is not in a selected memory");
            }
            answerVerdicts.add(new JudgeAdapter.AnswerVerdict(id, verdict, evidence, reason));
        }
        return new JudgeAdapter.Judgment(fixture.id(), model, family, List.copyOf(matches), List.copyOf(issues),
                List.copyOf(missing), List.copyOf(review), List.copyOf(answerVerdicts), text(root, "summary"));
    }

    private static JsonNode array(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isArray()) throw new IllegalArgumentException("Judge response needs array: " + name);
        return value;
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException("Judge response needs " + name);
        return value.asText();
    }
}
