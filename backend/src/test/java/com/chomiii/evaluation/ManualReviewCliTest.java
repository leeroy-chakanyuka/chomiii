package com.chomiii.evaluation;

import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "eval.reviewFile", matches = ".+")
class ManualReviewCliTest {
    @Test
    void regradeExistingRunWithoutProviderCalls() throws Exception {
        String source = System.getProperty("eval.sourceRun");
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Set -Deval.sourceRun to the report directory");
        var reviewed = new ManualReview().apply(Path.of(source), Path.of(System.getProperty("eval.reviewFile")));
        Path report = new EvaluationReportWriter().write(reviewed, Path.of("target", "evals"),
                Arrays.asList(System.getenv("GEMINI_API_KEY"), System.getenv("EVAL_JUDGE_API_KEY")));
        System.out.println("Manual review report: " + report.toAbsolutePath());
    }
}
