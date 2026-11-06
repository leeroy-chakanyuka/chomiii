package com.chomiii.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "eval.suite", matches = "planning-review")
class PlanningReviewCliTest {
    @Test
    void validateAndWriteSeparateReviewedReport() throws Exception {
        String source = System.getProperty("eval.sourceRun");
        String reviewFile = System.getProperty("eval.reviewFile");
        if (source == null || reviewFile == null) throw new IllegalArgumentException("A source run and review file are required");
        var mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        var root = Path.of(source);
        var metadata = mapper.readValue(root.resolve("run-metadata.json").toFile(), PlanningEvaluationSuite.Metadata.class);
        var cases = mapper.readValue(root.resolve("results.json").toFile(),
                new TypeReference<java.util.List<PlanningEvaluationSuite.Case>>() {});
        var review = mapper.readValue(Path.of(reviewFile).toFile(), PlanningReview.Review.class);
        var report = new EvaluationReportWriter().writePlanningReview(new PlanningEvaluationSuite.Run(metadata, cases),
                review, Path.of("target", "evals"), Arrays.asList(System.getenv("GEMINI_API_KEY"), System.getenv("EVAL_JUDGE_API_KEY")));
        System.out.println("Planning review report: " + report.toAbsolutePath());
    }
}
