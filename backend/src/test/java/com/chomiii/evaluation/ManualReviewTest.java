package com.chomiii.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManualReviewTest {
    @TempDir Path directory;

    @Test
    void regradesRecordedResultsUsingValidatedHumanEvidence() throws Exception {
        var properties = new Properties();
        properties.setProperty("eval.fixture", "m2-explicit-appointments-001");
        var run = new EvaluationSuite().run(EvaluationConfig.from(properties, Map.of()), Map.of());
        Path report = new EvaluationReportWriter().write(run, directory, List.of());
        var json = new ObjectMapper();
        var decisions = json.readTree(report.resolve("manual-review-template.json").toFile());
        var row = (ObjectNode) decisions.get(0);
        String actualId = run.cases().getFirst().observation().memories().getFirst().id();
        row.putArray("needsHumanReview");
        row.withArray("matches").addObject().put("expectedId", "appointment-time").put("actualId", actualId)
                .put("verdict", "supported").put("evidence", "I prefer appointments after ten")
                .put("reason", "The user stated the preference.");
        row.put("summary", "Reviewed by a person");
        Path decisionFile = directory.resolve("decisions.json");
        json.writeValue(decisionFile.toFile(), decisions);

        var reviewed = new ManualReview().apply(report, decisionFile);
        assertThat(reviewed.cases().getFirst().judgment().family()).isEqualTo("manual");
        assertThat(reviewed.cases().getFirst().observation().score().truePositives()).isEqualTo(1);
        assertThat(reviewed.metadata().judge()).contains("human review");
    }
}
