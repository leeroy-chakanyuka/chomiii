package com.chomiii.evaluation;

import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Explicitly copies a saved synthetic report to the reviewable report directory, with redaction. */
@EnabledIfSystemProperty(named = "eval.suite", matches = "planning-publish")
class PlanningPublishCliTest {
    @Test
    void publishAnExistingPlanningReport() throws Exception {
        String source = System.getProperty("eval.sourceRun");
        if (source == null) throw new IllegalArgumentException("A source run is required");
        for (String entry : source.split(",")) {
            var report = new EvaluationReportWriter().publishPlanning(Path.of(entry.strip()),
                    Path.of("..", "evals", "reports", "planning"),
                    Arrays.asList(System.getenv("GEMINI_API_KEY"), System.getenv("EVAL_JUDGE_API_KEY")));
            System.out.println("Reviewable planning report: " + report.toAbsolutePath());
        }
    }
}
