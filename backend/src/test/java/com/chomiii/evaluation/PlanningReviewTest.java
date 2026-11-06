package com.chomiii.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanningReviewTest {
    @TempDir Path directory;
    @Test
    void reportsOnlyFullyReviewedMetricsAndRequiresVerbatimEvidence() throws Exception {
        var properties = new Properties();
        properties.setProperty("eval.fixture", "memory-appointment");
        var run = new PlanningEvaluationSuite().run(EvaluationConfig.from(properties, Map.of()), Map.of());
        var grade = new PlanningReview.Grade(PlanningReview.Metric.PRIMARY_GOAL, 1, 1,
                "The review records the proposed goal.", List.of("Scripted example"));
        var decision = new PlanningReview.Decision("memory-appointment", 1, "oracle", List.of(grade));
        var review = new PlanningReview.Review("Synthetic test reviewer", true, List.of(decision));
        var result = new PlanningReview().apply(run, review);
        assertThat(result.scores().get("oracle").get(PlanningReview.Metric.PRIMARY_GOAL).value()).isEqualTo(1.0);
        assertThat(result.scores().get("oracle").get(PlanningReview.Metric.NECESSARY_STEPS).value()).isNull();
        assertThat(result.scores().get("actual_m2").get(PlanningReview.Metric.PRIMARY_GOAL).value()).isNull();
        var notIndependent = new PlanningReview.Review("Author", false, List.of(decision));
        assertThat(new PlanningReview().apply(run, notIndependent).scores().get("oracle")
                .get(PlanningReview.Metric.PRIMARY_GOAL).value()).isNull();
        var invented = new PlanningReview.Grade(PlanningReview.Metric.PRIMARY_GOAL, 1, 1,
                "Unverifiable", List.of("invented quote not in result"));
        assertThatThrownBy(() -> new PlanningReview().apply(run, new PlanningReview.Review("Reviewer", true,
                List.of(new PlanningReview.Decision("memory-appointment", 1, "oracle", List.of(invented))))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canReviewASavedRunWithoutChangingTheOriginal() throws Exception {
        var properties = new Properties();
        properties.setProperty("eval.fixture", "memory-appointment");
        var run = new PlanningEvaluationSuite().run(EvaluationConfig.from(properties, Map.of()), Map.of());
        var writer = new EvaluationReportWriter();
        Path source = writer.writePlanning(run, directory, List.of());
        String original = Files.readString(source.resolve("summary.md"));
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var metadata = json.readValue(source.resolve("run-metadata.json").toFile(), PlanningEvaluationSuite.Metadata.class);
        var cases = json.readValue(source.resolve("results.json").toFile(),
                new com.fasterxml.jackson.core.type.TypeReference<List<PlanningEvaluationSuite.Case>>() {});
        var review = new PlanningReview.Review("Reviewer", true, List.of());
        Path reviewed = writer.writePlanningReview(new PlanningEvaluationSuite.Run(metadata, cases), review, directory, List.of());
        assertThat(Files.readString(source.resolve("summary.md"))).isEqualTo(original);
        assertThat(Files.readString(reviewed.resolve("summary.md")))
                .contains("Scripted runs do not measure model quality", "PRIMARY_GOAL: not measured");
    }
}
