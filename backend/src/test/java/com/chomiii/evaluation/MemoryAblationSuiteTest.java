package com.chomiii.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.chomiii.memory.HybridMemorySearch;
import com.chomiii.memory.MemorySearchTool;
import com.chomiii.memory.PersonalMemory;
import com.chomiii.memory.embedding.EmbeddingProvider;
import com.chomiii.memory.embedding.EmbeddingVector;
import com.chomiii.tools.ToolTurn;
import com.chomiii.tools.ToolRegistry;
import com.chomiii.tools.ToolExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Retrieval-only ablation over the existing synthetic M2 fixtures and real JDBC storage. */
@EnabledIfEnvironmentVariable(named = "EVAL_DISPOSABLE_DB", matches = "true")
@EnabledIfSystemProperty(named = "eval.ablation", matches = "true")
class MemoryAblationSuiteTest {
    private static final int REPEATS = 5;
    private static final List<String> ARMS = List.of("A", "B", "C", "D");
    private static final List<String> DISTRACTORS = List.of(
            "I collect postage stamps", "I have a red bicycle", "I enjoy jazz music");

    @Test
    void comparesTheSameFixturesWithAndWithoutVectorsAndScriptedToolCalls() throws Exception {
        var all = new FixtureLoader().loadAll(Path.of("..", "evals", "fixtures", "m2"));
        var categoryCounts = new HashMap<String, Integer>();
        var fixtures = all.stream().filter(fixture -> !fixture.retrievalChecks().isEmpty())
                .filter(fixture -> fixture.retrievalChecks().getFirst().query().length() <= 300)
                .filter(fixture -> categoryCounts.merge(fixture.category(), 1, Integer::sum) <= 4).toList();
        var totals = new LinkedHashMap<String, Stats>();
        ARMS.forEach(arm -> totals.put(arm, new Stats()));
        int documentCalls = 0;
        int checks = 0;
        int privacyFailures = 0;
        int fallbackChecks = 0;
        int fallbackFailures = 0;
        try (var database = DisposableEvaluationDatabase.start()) {
            for (var fixture : fixtures) {
                try (var session = database.session()) {
                    var memories = database.memories();
                    var store = database.embeddings();
                    var byExpectedId = new HashMap<String, PersonalMemory>();
                    for (var expected : fixture.expectedMemories()) {
                        if (Boolean.TRUE.equals(expected.inScope()) && "active".equals(expected.expectedStatus())
                                && expected.statement().length() <= 500) {
                            byExpectedId.put(expected.id(), memories.create(session.owner(), expected.statement()));
                        }
                    }
                    for (int index = 0; index < DISTRACTORS.size(); index++) {
                        byExpectedId.put("__distractor_" + index, memories.create(session.owner(), DISTRACTORS.get(index)));
                    }
                    var fake = new FakeProvider();
                    while (true) {
                        var job = store.claim(session.owner());
                        if (job.isEmpty()) break;
                        assertThat(store.complete(job.get(), fake.document(job.get().content(), "synthetic"))).isTrue();
                        documentCalls++;
                    }
                    var semanticB = new HybridMemorySearch(memories, store, fake, true);
                    var semanticC = new HybridMemorySearch(memories, store, fake, true);
                    var lexical = new HybridMemorySearch(memories, store, fake, false);
                    var check = fixture.retrievalChecks().getFirst();
                    Set<String> expected = new HashSet<>(check.expectedMemoryIds());
                    if (!memories.forChat(session.other(), check.query()).isEmpty()
                            || !semanticB.search(session.other(), check.query(), null, 5, "synthetic").isEmpty()) {
                        privacyFailures++;
                    }
                    checks++;
                    for (int repeat = 0; repeat < REPEATS; repeat++) {
                        for (int offset = 0; offset < ARMS.size(); offset++) {
                            String arm = ARMS.get((repeat + offset) % ARMS.size());
                            int beforeCalls = fake.queryCalls.get();
                            long started = System.nanoTime();
                            String context = memories.forChat(session.owner(), check.query());
                            if ("B".equals(arm) || "C".equals(arm)) {
                                var semantic = "B".equals(arm) ? semanticB : semanticC;
                                context = semantic.augment(session.owner(), check.query(), "synthetic", context).context();
                            }
                            boolean toolCalled = false;
                            boolean toolFailed = false;
                            if ("C".equals(arm) || "D".equals(arm)) {
                                // A scripted tool call, not a model decision.
                                toolCalled = true;
                                var hybrid = "C".equals(arm) ? semanticC : lexical;
                                var tool = new MemorySearchTool(memories, hybrid);
                                var toolContext = new ToolExecutionContext(session.owner(), session.conversation(),
                                        java.util.UUID.randomUUID(), "synthetic", true, ignored -> {});
                                var result = new ToolTurn(new ToolRegistry(List.of(tool)), toolContext, false)
                                        .invoke("memory.search", Map.of("query", check.query()));
                                toolFailed = result.containsKey("error");
                                context += new ObjectMapper().writeValueAsString(result);
                            }
                            long elapsed = System.nanoTime() - started;
                            var selected = new HashSet<String>();
                            for (var entry : byExpectedId.entrySet()) {
                                if (contextContains(context, entry.getValue())) selected.add(entry.getKey());
                            }
                            totals.get(arm).add(expected, selected, elapsed, toolCalled, toolFailed,
                                    fake.queryCalls.get() - beforeCalls);
                        }
                    }
                    var failingProvider = new FakeProvider(true);
                    var failing = new HybridMemorySearch(memories, store, failingProvider, true);
                    var expectedFallback = memories.search(session.owner(), check.query(), null, 20);
                    var actualFallback = failing.search(session.owner(), check.query(), null, 20, "synthetic");
                    fallbackChecks++;
                    if (!actualFallback.equals(expectedFallback) || failingProvider.queryCalls.get() != 1) fallbackFailures++;
                }
            }
        }
        var output = new LinkedHashMap<String, Object>();
        output.put("dataset", "M2 fixture retrieval checks, first four per category");
        output.put("datasetSha256", EvaluationSuite.datasetHash(all));
        output.put("commit", EvaluationSuite.gitCommit());
        output.put("workingTreeDirty", EvaluationSuite.gitDirty());
        output.put("fixtures", fixtures.size());
        output.put("fixtureIds", fixtures.stream().map(EvaluationFixture::id).toList());
        output.put("checks", checks);
        output.put("repeats", REPEATS);
        output.put("distractorsPerFixture", DISTRACTORS.size());
        output.put("documentEmbeddingCalls", documentCalls);
        output.put("privacyFailures", privacyFailures);
        output.put("fallbackChecks", fallbackChecks);
        output.put("fallbackFailures", fallbackFailures);
        output.put("limitations", List.of("Oracle-seeded canonical facts, three unreviewed distractors per fixture, and deterministic fake embeddings.",
                "Tool decisions are scripted, not generated by a model.",
                "Outdated-memory retrieval, answer quality, clarification questions, provider tokens, and end-to-end latency are not measured."));
        var metrics = new LinkedHashMap<String, Object>();
        totals.forEach((arm, stats) -> metrics.put(arm, stats.report()));
        output.put("arms", metrics);
        Path report = Path.of("target", "evals", "memory-ablation.json");
        Files.createDirectories(report.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(report.toFile(), output);
        System.out.println("Ablation report: " + report.toAbsolutePath());
        assertThat(checks).isPositive();
        assertThat(privacyFailures).isZero();
        assertThat(fallbackFailures).isZero();
    }

    private static boolean contextContains(String context, PersonalMemory memory) {
        return context.contains(memory.id().toString());
    }

    private static final class Stats {
        private int truePositive, falsePositive, falseNegative, tools, errors, queries;
        private final List<Long> durations = new ArrayList<>();
        void add(Set<String> expected, Set<String> selected, long elapsed, boolean tool, boolean error, int queryCalls) {
            truePositive += (int) selected.stream().filter(expected::contains).count();
            falsePositive += (int) selected.stream().filter(id -> !expected.contains(id)).count();
            falseNegative += (int) expected.stream().filter(id -> !selected.contains(id)).count();
            if (tool) tools++;
            if (error) errors++;
            queries += queryCalls;
            durations.add(elapsed);
        }
        Map<String, Object> report() {
            var sorted = durations.stream().sorted(Comparator.naturalOrder()).toList();
            return Map.ofEntries(Map.entry("observations", sorted.size()), Map.entry("truePositive", truePositive),
                    Map.entry("falsePositive", falsePositive), Map.entry("falseNegative", falseNegative),
                    Map.entry("precision", ratio(truePositive, truePositive + falsePositive)),
                    Map.entry("recall", ratio(truePositive, truePositive + falseNegative)),
                    Map.entry("p50Millis", percentile(sorted, 0.50)), Map.entry("p95Millis", percentile(sorted, 0.95)),
                    Map.entry("minMillis", percentile(sorted, 0.0)), Map.entry("maxMillis", percentile(sorted, 1.0)),
                    Map.entry("toolCalls", tools), Map.entry("toolErrors", errors), Map.entry("queryEmbeddings", queries));
        }
        private static double ratio(int numerator, int denominator) { return denominator == 0 ? 0 : (double) numerator / denominator; }
        private static double percentile(List<Long> sorted, double fraction) {
            return sorted.isEmpty() ? 0 : sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * fraction) - 1)) / 1_000_000.0;
        }
    }

    private static final class FakeProvider implements EmbeddingProvider {
        private final AtomicInteger queryCalls = new AtomicInteger();
        private final boolean fail;
        FakeProvider() { this(false); }
        FakeProvider(boolean fail) { this.fail = fail; }
        @Override public Descriptor descriptor() { return new Descriptor("gemini-embedding-2", 768, "search-v1"); }
        @Override public float[] document(String text, String key) { return vector(text); }
        @Override public float[] query(String text, String key) {
            queryCalls.incrementAndGet();
            if (fail) throw new IllegalStateException("synthetic provider outage");
            return vector(text);
        }
        private static float[] vector(String text) {
            float[] values = new float[768];
            for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
                if (word.length() < 4) continue;
                String feature = switch (word) {
                    case "taco", "tacos", "dinner", "supper", "meal", "cook" -> "food";
                    case "interview", "engineer", "developer", "software" -> "work";
                    case "monday", "tuesday", "morning", "schedule", "calendar" -> "schedule";
                    default -> word;
                };
                values[Math.floorMod(feature.hashCode(), values.length)] += 1;
            }
            boolean empty = true;
            for (float value : values) if (value != 0) { empty = false; break; }
            if (empty) values[0] = 1;
            return EmbeddingVector.normalized(values, 768);
        }
    }
}
