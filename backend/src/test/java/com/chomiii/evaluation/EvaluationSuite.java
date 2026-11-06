package com.chomiii.evaluation;

import com.chomiii.provider.gemini.GeminiProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class EvaluationSuite {
    record Case(String fixtureId, String category, String difficulty, String importance, int repetition,
            String status, String error, List<EvaluationFixture.ExpectedMemory> expected,
            List<EvaluationFixture.ForbiddenMemory> forbidden, MemoryEvaluationRunner.Result observation,
            JudgeAdapter.Judgment judgment) {}
    record Metadata(String runId, String startedAt, String commit, boolean workingTreeDirty,
            String datasetVersion, String datasetSha256,
            String mode, String provider, String model, String judge, String database,
            int selectedFixtures, int attempted, int completed, int skipped, int failed,
            int repetitions, int maxFixtures, int maxProviderCalls, int timeoutSeconds,
            int minProviderIntervalMillis, int maxProviderInputBytes,
            Map<LiveBudget.Kind, Integer> providerCalls, long elapsedMillis, String tokenUsage,
            String judgeTokenUsage, String cost, String seed) {}
    record Run(Metadata metadata, List<Case> cases) {}

    Run run(EvaluationConfig config, Map<String, String> environment) throws Exception {
        var fixtures = new FixtureLoader().loadAll(Path.of("..", "evals", "fixtures", "m2"));
        String datasetHash = datasetHash(fixtures);
        var requested = config.fixture() == null ? java.util.Set.<String>of()
                : java.util.Arrays.stream(config.fixture().split(",", -1)).map(String::strip)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (requested.contains("")) throw new IllegalArgumentException("Fixture IDs must not be empty");
        var selected = fixtures.stream()
                .filter(fixture -> requested.isEmpty() || requested.contains(fixture.id()))
                .filter(fixture -> config.category() == null || config.category().equals(fixture.category())).toList();
        if (selected.isEmpty()) throw new IllegalArgumentException("No fixtures match the requested filter");
        if (!requested.isEmpty() && selected.size() != requested.size()) {
            throw new IllegalArgumentException("A requested fixture ID was not found in the selected category");
        }
        var started = Instant.now();
        var cases = new ArrayList<Case>();
        var budget = new LiveBudget("live".equals(config.mode()) || config.judge(), config.maxCalls(),
                config.minProviderIntervalMillis(), config.maxProviderInputBytes());
        var judgeConfig = config.judge() ? IndependentJudge.Config.from(environment, config.timeoutSeconds()) : null;
        var judge = judgeConfig == null ? null : new IndependentJudge(judgeConfig, environment.get("EVAL_JUDGE_API_KEY"), budget);
        var runner = new MemoryEvaluationRunner();
        DisposableEvaluationDatabase database = null;
        try {
            if (!"offline".equals(config.mode())) database = DisposableEvaluationDatabase.start();
            for (int index = 0; index < selected.size(); index++) {
                var fixture = selected.get(index);
                for (int repeat = 1; repeat <= config.repetitions(); repeat++) {
                    if (index >= config.maxFixtures() || (("live".equals(config.mode()) || config.judge()) && budget.exhausted())) {
                        cases.add(new Case(fixture.id(), fixture.category(), fixture.difficulty(), fixture.importance(), repeat,
                                "skipped", index >= config.maxFixtures() ? "fixture_limit"
                                        : budget.rateLimited() ? "provider_rate_limited"
                                        : budget.providerFailed() ? "provider_failure" : "provider_budget_exhausted",
                                fixture.expectedMemories(), fixture.forbiddenMemories(), null, null));
                        continue;
                    }
                    try {
                        MemoryEvaluationRunner.Result observation = switch (config.mode()) {
                            case "offline" -> runner.runOffline(fixture);
                            case "integration" -> runner.runDatabase(fixture, database, null, null,
                                    config.model(), "offline-test-key", config.timeoutSeconds());
                            case "live" -> runner.runDatabase(fixture, database, new GeminiProvider(), budget,
                                    config.model(), environment.get("GEMINI_API_KEY"), config.timeoutSeconds());
                            default -> throw new IllegalArgumentException("Unknown evaluation mode");
                        };
                        boolean failed = observation.turns().stream().anyMatch(turn -> turn.failure() != null
                                        || java.util.Set.of("invalid_json", "invalid_output", "too_many_candidates", "provider_result_unavailable")
                                                .contains(turn.validationOutcome()))
                                || observation.retrievals().stream().anyMatch(retrieval -> retrieval.failure() != null)
                                || observation.lifecycle().stream().anyMatch(action -> "not_exercised".equals(action.outcome()));
                        JudgeAdapter.Judgment judgment = null;
                        String error = failed ? "incomplete_observation" : null;
                        if (judge != null && !failed) {
                            try {
                                judgment = judge.judge(fixture, observation.memories(), observation.retrievals());
                                var score = new MemoryScorer().score(fixture,
                                        MemoryEvaluationRunner.scoringMemories(observation.memories(), observation.turns()), judgment);
                                observation = new MemoryEvaluationRunner.Result(observation.fixtureId(), observation.mode(),
                                        observation.turns(), observation.memories(), observation.retrievals(), observation.lifecycle(),
                                        observation.extractionScore(), score, observation.limitations());
                            } catch (Exception judgeFailure) {
                                failed = true;
                                error = "judge_" + judgeFailure.getClass().getSimpleName();
                            }
                        }
                        cases.add(new Case(fixture.id(), fixture.category(), fixture.difficulty(), fixture.importance(), repeat,
                                failed ? "failed" : "completed", error,
                                fixture.expectedMemories(), fixture.forbiddenMemories(), observation, judgment));
                    } catch (Exception failure) {
                        cases.add(new Case(fixture.id(), fixture.category(), fixture.difficulty(), fixture.importance(), repeat,
                                "failed", failure.getClass().getSimpleName(), fixture.expectedMemories(),
                                fixture.forbiddenMemories(), null, null));
                    }
                }
            }
        } finally {
            if (database != null) database.close();
        }
        int completed = (int) cases.stream().filter(item -> "completed".equals(item.status())).count();
        int skipped = (int) cases.stream().filter(item -> "skipped".equals(item.status())).count();
        int failed = (int) cases.stream().filter(item -> "failed".equals(item.status())).count();
        String databaseName = "offline".equals(config.mode()) ? "in-memory fake"
                : environment.getOrDefault("CHOMIII_DB_URL", "unknown").startsWith("jdbc:mysql:") ? "mysql" : "postgresql";
        var providerCalls = new java.util.EnumMap<LiveBudget.Kind, Integer>(LiveBudget.Kind.class);
        for (var kind : LiveBudget.Kind.values()) providerCalls.put(kind, budget.counts().getOrDefault(kind, 0));
        var metadata = new Metadata(started.toString().replace(':', '-') + "-" + UUID.randomUUID(), started.toString(),
                gitCommit(), gitDirty(), "m2-fixtures-v1", datasetHash, config.mode(),
                "live".equals(config.mode()) ? "gemini" : "scripted", config.model(),
                judgeConfig == null ? "not independently graded" : "declared " + judgeConfig.family() + "/" + judgeConfig.model(),
                databaseName, selected.size(), completed + failed, completed, skipped, failed,
                config.repetitions(), config.maxFixtures(), config.maxCalls(), config.timeoutSeconds(),
                config.minProviderIntervalMillis(), config.maxProviderInputBytes(),
                Map.copyOf(providerCalls), java.time.Duration.between(started, Instant.now()).toMillis(),
                "not measured", judgeUsage(judge), estimatedCost(judge, environment), "not supported by provider boundary");
        return new Run(metadata, List.copyOf(cases));
    }

    private static String judgeUsage(IndependentJudge judge) {
        if (judge == null || judge.usage().isEmpty()
                || judge.usage().stream().anyMatch(usage -> usage.inputTokens() == null || usage.outputTokens() == null)) {
            return "not measured";
        }
        long input = judge.usage().stream().mapToLong(IndependentJudge.Usage::inputTokens).sum();
        long output = judge.usage().stream().mapToLong(IndependentJudge.Usage::outputTokens).sum();
        return "input=" + input + ", output=" + output;
    }

    private static String estimatedCost(IndependentJudge judge, Map<String, String> environment) {
        if (judge == null || judge.usage().isEmpty()
                || judge.usage().stream().anyMatch(usage -> usage.inputTokens() == null || usage.outputTokens() == null)) {
            return "not measured";
        }
        String inputPrice = environment.get("EVAL_JUDGE_INPUT_USD_PER_MILLION");
        String outputPrice = environment.get("EVAL_JUDGE_OUTPUT_USD_PER_MILLION");
        if (inputPrice == null || outputPrice == null) return "not measured";
        try {
            var inputRate = new java.math.BigDecimal(inputPrice);
            var outputRate = new java.math.BigDecimal(outputPrice);
            if (inputRate.signum() < 0 || outputRate.signum() < 0) return "not measured: invalid configured price";
            long input = judge.usage().stream().mapToLong(IndependentJudge.Usage::inputTokens).sum();
            long output = judge.usage().stream().mapToLong(IndependentJudge.Usage::outputTokens).sum();
            var dollars = inputRate.multiply(java.math.BigDecimal.valueOf(input))
                    .add(outputRate.multiply(java.math.BigDecimal.valueOf(output)))
                    .divide(java.math.BigDecimal.valueOf(1_000_000), 8, java.math.RoundingMode.HALF_UP);
            return "judge estimate USD " + dollars + " (configured per-million rates " + inputRate + "/" + outputRate
                    + "); total cost not measured";
        } catch (NumberFormatException invalid) {
            return "not measured: invalid configured price";
        }
    }

    static String datasetHash(List<EvaluationFixture> fixtures) throws Exception {
        byte[] bytes = new ObjectMapper().writeValueAsBytes(fixtures);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static String gitCommit() {
        try {
            var process = new ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return process.waitFor() == 0 && output.matches("[0-9a-f]{40}") ? output : "unavailable";
        } catch (Exception unavailable) {
            return "unavailable";
        }
    }

    static boolean gitDirty() {
        try {
            var process = new ProcessBuilder("git", "status", "--porcelain", "--untracked-files=normal")
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.waitFor() != 0 || !output.isBlank();
        } catch (Exception unavailable) {
            return true;
        }
    }
}
