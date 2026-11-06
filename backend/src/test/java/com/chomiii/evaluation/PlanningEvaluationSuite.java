package com.chomiii.evaluation;

import com.chomiii.memory.PersonalMemory;
import com.chomiii.planning.Plan;
import com.chomiii.planning.PlanningService;
import com.chomiii.provider.ChatProvider;
import com.chomiii.provider.gemini.GeminiProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Planning extension to the M2 fixture, memory runner, budget and report infrastructure. */
final class PlanningEvaluationSuite {
    record Memory(String id, String content, String source, String status, String updatedAt) {}
    record Case(String fixtureId, String category, int repetition, String condition, String status, String error,
            JsonNode expectations, List<Memory> selectedMemories, MemoryEvaluationRunner.Result memoryReplay,
            Plan plan, String providerOutput, boolean providerOutputTruncated,
            Long memoryRetrievalMillis, long contextMillis, long pacingAndOpenMillis, long providerMillis, long validationMillis) {}
    record Metadata(String runId, String startedAt, String commit, boolean workingTreeDirty, String datasetSha256,
            String mode, String model, String database, int selectedFixtures, int repetitions,
            int maxProviderCalls, int minProviderIntervalMillis, int maxProviderInputBytes,
            Map<LiveBudget.Kind, Integer> providerCalls, long elapsedMillis, String semanticGrading,
            String tokenUsage, String estimatedCost) {}
    record Run(Metadata metadata, List<Case> cases) {}

    private static final String SCRIPTED_PLAN = """
            {"goal":"Scripted example","summary":"This is not a model-quality measurement.",
             "memoryReferences":[],"constraints":[],"missingInformation":[],"steps":[
             {"id":"s1","title":"Prepare the next step","capability":"user.prepare","dependsOn":[]}]}
            """;

    Run run(EvaluationConfig config, Map<String, String> environment) throws Exception {
        if (config.judge()) throw new IllegalArgumentException("Planning semantic judging is not implemented");
        var loader = new FixtureLoader();
        var memoryFixtures = loader.loadAll(Path.of("..", "evals", "fixtures", "m2"));
        var byId = memoryFixtures.stream().collect(Collectors.toMap(EvaluationFixture::id, item -> item));
        var all = loader.loadPlanning(Path.of("..", "evals", "fixtures", "planning", "scenarios.json"), memoryFixtures);
        var selected = select(all, config);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(new ObjectMapper().writeValueAsBytes(all)));
        Instant started = Instant.now();
        var results = new ArrayList<Case>();
        var budget = new LiveBudget("live".equals(config.mode()), config.maxCalls(),
                config.minProviderIntervalMillis(), config.maxProviderInputBytes());
        var runner = new MemoryEvaluationRunner();
        var planner = new PlanningService();
        ChatProvider live = "live".equals(config.mode()) ? new GeminiProvider() : null;
        DisposableEvaluationDatabase database = null;
        try (var timer = Executors.newSingleThreadScheduledExecutor()) {
            if (!"offline".equals(config.mode())) database = DisposableEvaluationDatabase.start();
            for (int index = 0; index < selected.size(); index++) {
                var fixture = selected.get(index);
                var source = byId.get(fixture.sourceFixture());
                String question = fixture.planning().path("instruction").asText();
                for (int repetition = 1; repetition <= config.repetitions(); repetition++) {
                    if (index >= config.maxFixtures() || live != null && budget.exhausted()) {
                        for (String condition : List.of("oracle", "actual_m2")) {
                            results.add(skipped(fixture, repetition, condition, "fixture_limit_or_provider_stopped"));
                        }
                        continue;
                    }
                    var oracle = oracleMemories(fixture, source);
                    results.add(plan(fixture, repetition, "oracle", oracle, null, null, planner, live, budget, timer, config, environment));
                    if (live != null && budget.exhausted()) {
                        results.add(skipped(fixture, repetition, "actual_m2", "provider_stopped"));
                        continue;
                    }
                    try {
                        var replay = runner.runForPlanning(source, database, live, budget, config.model(),
                                live == null ? "offline-test-key" : environment.get("GEMINI_API_KEY"), config.timeoutSeconds(), question);
                        boolean incomplete = replay.observation().turns().stream().anyMatch(turn -> turn.failure() != null
                                || Set.of("invalid_json", "invalid_output", "too_many_candidates", "provider_result_unavailable")
                                .contains(turn.validationOutcome()))
                                || replay.observation().retrievals().stream().anyMatch(retrieval -> retrieval.failure() != null);
                        if (incomplete) {
                            results.add(new Case(fixture.id(), fixture.category(), repetition, "actual_m2", "failed",
                                    "memory_replay_incomplete", fixture.planning(), memoryReport(replay.selected()),
                                    replay.observation(), null, null, false, replay.retrievalMillis(), 0, 0, 0, 0));
                        } else {
                            results.add(plan(fixture, repetition, "actual_m2", replay.selected(), replay.observation(), replay.retrievalMillis(),
                                    planner, live, budget, timer, config, environment));
                        }
                    } catch (Exception failure) {
                        results.add(new Case(fixture.id(), fixture.category(), repetition, "actual_m2", "failed",
                                failure.getClass().getSimpleName(), fixture.planning(), List.of(), null, null, null, false, null, 0, 0, 0, 0));
                    }
                }
            }
        } finally {
            if (database != null) database.close();
        }
        var counts = new java.util.EnumMap<LiveBudget.Kind, Integer>(LiveBudget.Kind.class);
        for (var kind : LiveBudget.Kind.values()) counts.put(kind, budget.counts().getOrDefault(kind, 0));
        var metadata = new Metadata(started.toString().replace(':', '-') + "-planning-" + UUID.randomUUID(),
                started.toString(), EvaluationSuite.gitCommit(), EvaluationSuite.gitDirty(), hash,
                config.mode(), config.model(), "offline".equals(config.mode()) ? "in-memory fake" : "disposable database",
                selected.size(), config.repetitions(), config.maxCalls(), config.minProviderIntervalMillis(),
                config.maxProviderInputBytes(), Map.copyOf(counts), java.time.Duration.between(started, Instant.now()).toMillis(),
                "not measured; independent review required", "not measured", "not measured");
        return new Run(metadata, List.copyOf(results));
    }

    static List<PlanningFixture> select(List<PlanningFixture> all, EvaluationConfig config) {
        Set<String> requested = config.fixture() == null ? Set.of() : Set.of(config.fixture().split(","));
        var selected = all.stream().filter(item -> requested.isEmpty() || requested.contains(item.id()))
                .filter(item -> !"live".equals(config.mode()) || !requested.isEmpty() || !item.heldOut())
                .filter(item -> config.category() == null || config.category().equals(item.category())).toList();
        if (selected.isEmpty() || !requested.isEmpty() && selected.size() != requested.size()) {
            throw new IllegalArgumentException("Unknown or mismatched planning fixture");
        }
        return selected;
    }

    private static Case plan(PlanningFixture fixture, int repetition, String condition, List<PersonalMemory> memories,
            MemoryEvaluationRunner.Result replay, Long memoryRetrievalMillis, PlanningService planner, ChatProvider live, LiveBudget budget,
            java.util.concurrent.ScheduledExecutorService timer, EvaluationConfig config, Map<String, String> environment) {
        long contextStart = System.nanoTime();
        String question = fixture.planning().path("instruction").asText();
        EvaluationProvider provider = null;
        long contextMillis = 0;
        long providerStart = 0;
        long openMillis = 0;
        try {
            var capabilities = new java.util.HashSet<String>(Set.of("user.prepare", "user.decide"));
            fixture.planning().path("toolsAvailable").forEach(item -> capabilities.add(item.asText()));
            var input = planner.input(config.model(), List.of(), question, memories, capabilities);
            contextMillis = millis(contextStart);
            provider = new EvaluationProvider(Map.of(), Map.of(question, SCRIPTED_PLAN),
                    live, budget, timer, config.timeoutSeconds());
            providerStart = System.nanoTime();
            try (var generation = provider.open(input, live == null ? "offline-test-key" : environment.get("GEMINI_API_KEY"))) {
                openMillis = millis(providerStart);
                var timed = planner.readTimed(generation, memories, capabilities);
                var call = provider.calls().getLast();
                return new Case(fixture.id(), fixture.category(), repetition, condition, "completed", null,
                        fixture.planning(), memoryReport(memories), replay, timed.plan(), call.output(), call.outputTruncated(),
                        memoryRetrievalMillis, contextMillis, openMillis, timed.providerMillis(), timed.validationMillis());
            }
        } catch (RuntimeException failure) {
            var calls = provider == null ? List.<EvaluationProvider.Call>of() : provider.calls();
            var call = calls.isEmpty() ? null : calls.getLast();
            return new Case(fixture.id(), fixture.category(), repetition, condition, "failed", failure.getClass().getSimpleName(),
                    fixture.planning(), memoryReport(memories), replay, null, call == null ? null : call.output(),
                    call != null && call.outputTruncated(), memoryRetrievalMillis, providerStart == 0 ? millis(contextStart) : contextMillis,
                    openMillis, call == null ? 0 : TimeUnit.NANOSECONDS.toMillis(call.durationNanos()), 0);
        }
    }

    private static List<PersonalMemory> oracleMemories(PlanningFixture fixture, EvaluationFixture source) {
        var requested = new java.util.HashSet<String>();
        fixture.planning().path("oracleMemoryIds").forEach(id -> requested.add(id.asText()));
        return source.expectedMemories().stream().filter(fact -> requested.contains(fact.id())).map(fact ->
                new PersonalMemory(UUID.nameUUIDFromBytes((fixture.id() + ":" + fact.id()).getBytes(StandardCharsets.UTF_8)),
                        fact.statement(), "user_explicit", Instant.EPOCH, Instant.EPOCH)).toList();
    }

    private static List<Memory> memoryReport(List<PersonalMemory> memories) {
        return memories.stream().map(memory -> new Memory(memory.id().toString(), memory.content(),
                memory.source(), memory.status(), memory.updatedAt().toString())).toList();
    }

    private static Case skipped(PlanningFixture fixture, int repetition, String condition, String error) {
        return new Case(fixture.id(), fixture.category(), repetition, condition, "skipped", error,
                fixture.planning(), List.of(), null, null, null, false, null, 0, 0, 0, 0);
    }

    private static long millis(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
}
