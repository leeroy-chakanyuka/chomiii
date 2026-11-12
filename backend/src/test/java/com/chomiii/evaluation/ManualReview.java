package com.chomiii.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Regrades saved observations without making a provider request. */
final class ManualReview {
    EvaluationSuite.Run apply(Path source, Path decisions) throws Exception {
        var json = new ObjectMapper();
        var metadata = json.readValue(source.resolve("run-metadata.json").toFile(), EvaluationSuite.Metadata.class);
        List<EvaluationSuite.Case> original = json.readValue(source.resolve("results.json").toFile(), new TypeReference<>() {});
        var fixtures = new FixtureLoader().loadAll(Path.of("..", "evals", "fixtures", "m2"));
        if (!metadata.datasetSha256().equals(EvaluationSuite.datasetHash(fixtures))) {
            throw new IllegalArgumentException("Fixture dataset changed since the original run");
        }
        Map<String, EvaluationFixture> byId = new HashMap<>();
        fixtures.forEach(fixture -> byId.put(fixture.id(), fixture));
        JsonNode input = json.readTree(decisions.toFile());
        if (!input.isArray()) throw new IllegalArgumentException("Manual review must be an array");
        Map<String, JsonNode> byCase = new HashMap<>();
        for (JsonNode item : input) {
            if (!item.path("fixtureId").isTextual() || !item.path("repetition").isIntegralNumber()
                    || item.path("repetition").asInt() < 1) throw new IllegalArgumentException("Invalid manual review case");
            String key = item.path("fixtureId").asText() + "#" + item.path("repetition").asInt();
            if (byCase.putIfAbsent(key, item) != null) throw new IllegalArgumentException("Duplicate manual review case");
        }
        var results = new ArrayList<EvaluationSuite.Case>();
        for (var item : original) {
            String key = item.fixtureId() + "#" + item.repetition();
            JsonNode decision = byCase.remove(key);
            if (decision == null || item.observation() == null) { results.add(item); continue; }
            EvaluationFixture fixture = byId.get(item.fixtureId());
            if (fixture == null) throw new IllegalArgumentException("Unknown fixture in original report");
            var observation = item.observation();
            var judgment = new JudgeResponseParser().parse(decision.toString(), fixture, observation.memories(),
                    observation.retrievals(), "human", "manual");
            var score = new MemoryScorer().score(fixture,
                    MemoryEvaluationRunner.scoringMemories(observation.memories(), observation.turns()), judgment);
            var rescored = new MemoryEvaluationRunner.Result(observation.fixtureId(), observation.mode(),
                    observation.turns(), observation.memories(), observation.retrievals(), observation.lifecycle(),
                    observation.extractionScore(), score, observation.limitations(), observation.providerUsage());
            boolean recoverJudgeFailure = item.error() != null && item.error().startsWith("judge_");
            results.add(new EvaluationSuite.Case(item.fixtureId(), item.category(), item.difficulty(), item.importance(),
                    item.repetition(), recoverJudgeFailure ? "completed" : item.status(), recoverJudgeFailure ? null : item.error(),
                    item.expected(), item.forbidden(), rescored, judgment));
        }
        if (!byCase.isEmpty()) throw new IllegalArgumentException("Manual review contains unknown cases");
        int completed = (int) results.stream().filter(item -> "completed".equals(item.status())).count();
        int failed = (int) results.stream().filter(item -> "failed".equals(item.status())).count();
        int skipped = (int) results.stream().filter(item -> "skipped".equals(item.status())).count();
        Instant now = Instant.now();
        var revised = new EvaluationSuite.Metadata(now.toString().replace(':', '-') + "-" + UUID.randomUUID(),
                now.toString(), EvaluationSuite.gitCommit(), EvaluationSuite.gitDirty(), metadata.datasetVersion(),
                metadata.datasetSha256(), metadata.mode(), metadata.provider(), metadata.model(),
                "human review of " + metadata.runId(), metadata.database(), metadata.selectedFixtures(),
                completed + failed, completed, skipped, failed, metadata.repetitions(), metadata.maxFixtures(),
                metadata.maxProviderCalls(), metadata.timeoutSeconds(), metadata.minProviderIntervalMillis(),
                metadata.maxProviderInputBytes(), metadata.providerCalls(), metadata.elapsedMillis(),
                metadata.tokenUsage(), metadata.judgeTokenUsage(), metadata.cost(), metadata.seed());
        return new EvaluationSuite.Run(revised, List.copyOf(results));
    }
}
