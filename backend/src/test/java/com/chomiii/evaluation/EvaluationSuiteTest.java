package com.chomiii.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Maven entry point. The default mode uses synthetic inputs and makes no network calls. */
class EvaluationSuiteTest {
    @Test
    void runSelectedEvaluationAndWriteReports() throws Exception {
        var config = EvaluationConfig.from(System.getProperties(), System.getenv());
        var run = new EvaluationSuite().run(config, System.getenv());
        String previous = System.getProperty("eval.compare");
        var report = new EvaluationReportWriter().write(run, Path.of("target", "evals"),
                Arrays.asList(System.getenv("GEMINI_API_KEY"), System.getenv("EVAL_JUDGE_API_KEY")),
                previous == null || previous.isBlank() ? null : Path.of(previous));
        System.out.println("Evaluation report: " + report.toAbsolutePath());
        assertThat(run.metadata().attempted()).isGreaterThan(0);
        assertThat(run.metadata().failed()).as("evaluation errors are recorded in " + report).isZero();
        assertThat(run.cases().stream().filter(item -> "provider_budget_exhausted".equals(item.error())).toList())
                .as("budget exhaustion is recorded in " + report).isEmpty();
    }
}
