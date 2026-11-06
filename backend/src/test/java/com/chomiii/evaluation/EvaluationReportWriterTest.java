package com.chomiii.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvaluationReportWriterTest {
    @TempDir Path directory;

    @Test
    void writesInspectableReportsAndRedactsSecrets() throws Exception {
        var fixture = new FixtureLoader().load(Path.of("..", "evals", "fixtures", "m2", "explicit-facts", "appointments.json"));
        var observation = new MemoryEvaluationRunner().runOffline(fixture);
        var item = new EvaluationSuite.Case(fixture.id(), fixture.category(), fixture.difficulty(), fixture.importance(),
                1, "completed", null, fixture.expectedMemories(), fixture.forbiddenMemories(), observation, null);
        var metadata = new EvaluationSuite.Metadata("example-run", "2026-11-05T00:00:00Z", "synthetic-secret-value", false,
                "m2-fixtures-v1", "hash", "offline", "scripted", "model", "not independently graded", "fake",
                1, 1, 1, 0, 0, 1, 1, 0, 60, 0, 1_000_000, Map.of(), 0, "not measured", "not measured", "not measured", "not supported");
        Path report = new EvaluationReportWriter().write(new EvaluationSuite.Run(metadata, List.of(item)), directory,
                List.of("synthetic-secret-value"));

        assertThat(Files.exists(report.resolve("run-metadata.json"))).isTrue();
        assertThat(Files.exists(report.resolve("results.json"))).isTrue();
        assertThat(Files.exists(report.resolve("summary.md"))).isTrue();
        assertThat(Files.exists(report.resolve("failures.md"))).isTrue();
        assertThat(new ObjectMapper().readTree(report.resolve("results.json").toFile())).hasSize(1);
        assertThat(Files.readString(report.resolve("run-metadata.json"))).doesNotContain("synthetic-secret-value")
                .contains("[REDACTED]");
        assertThat(Files.readString(report.resolve("summary.md")))
                .contains("do not measure model quality", "not independently graded", "not measured");
        assertThat(EvaluationReportWriter.redact("My password is fictional", List.of()))
                .isEqualTo("[REDACTED: sensitive content]");
    }

    @Test
    void doesNotMarkQualityGatesPassedWithoutCaseLevelReview() throws Exception {
        var fixture = new FixtureLoader().load(Path.of("..", "evals", "fixtures", "m2", "explicit-facts", "appointments.json"));
        var observation = new MemoryEvaluationRunner().runOffline(fixture);
        var item = new EvaluationSuite.Case(fixture.id(), fixture.category(), fixture.difficulty(), fixture.importance(),
                1, "completed", null, fixture.expectedMemories(), fixture.forbiddenMemories(), observation, null);
        var metadata = new EvaluationSuite.Metadata("unreviewed-run", "2026-11-05T00:00:00Z", "commit", false,
                "m2-fixtures-v1", "hash", "live", "gemini", "model", "declared other-family/model", "postgresql",
                1, 1, 1, 0, 0, 1, 1, 5, 60, 0, 1_000_000, Map.of(), 0, "not measured", "not measured", "not measured", "not supported");
        Path report = new EvaluationReportWriter().write(new EvaluationSuite.Run(metadata, List.of(item)), directory, List.of());
        assertThat(Files.readString(report.resolve("summary.md")))
                .contains("Final-memory precision >= 95%: NOT EVALUATED");
    }

    @Test
    void comparesOnlyCompatibleCleanRuns() throws Exception {
        var fixture = new FixtureLoader().load(Path.of("..", "evals", "fixtures", "m2", "explicit-facts", "appointments.json"));
        var observation = new MemoryEvaluationRunner().runOffline(fixture);
        var item = new EvaluationSuite.Case(fixture.id(), fixture.category(), fixture.difficulty(), fixture.importance(),
                1, "completed", null, fixture.expectedMemories(), fixture.forbiddenMemories(), observation, null);
        var first = metadata("first", false);
        var second = metadata("second", false);
        var dirty = metadata("dirty", true);
        var writer = new EvaluationReportWriter();
        Path baseline = writer.write(new EvaluationSuite.Run(first, List.of(item)), directory, List.of());
        Path comparable = writer.write(new EvaluationSuite.Run(second, List.of(item)), directory, List.of(), baseline);
        assertThat(Files.readString(comparable.resolve("summary.md")))
                .contains("Matched final-memory facts: prior 1, current 1");
        Path incompatible = writer.write(new EvaluationSuite.Run(dirty, List.of(item)), directory, List.of(), baseline);
        assertThat(Files.readString(incompatible.resolve("summary.md"))).contains("Not comparable");
    }

    private static EvaluationSuite.Metadata metadata(String id, boolean dirty) {
        return new EvaluationSuite.Metadata(id, "2026-11-05T00:00:00Z", "commit", dirty,
                "m2-fixtures-v1", "hash", "offline", "scripted", "model", "not independently graded", "fake",
                1, 1, 1, 0, 0, 1, 1, 0, 60, 0, 1_000_000, Map.of(), 0, "not measured", "not measured", "not measured", "not supported");
    }
}
