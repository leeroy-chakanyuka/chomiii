package com.chomiii.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class PlanningEvaluationSuiteTest {
    @Test
    void runsOracleAndActualMemorySeparatelyWithoutOracleFallback() throws Exception {
        var properties = new Properties();
        properties.setProperty("eval.fixture", "memory-appointment");
        var config = EvaluationConfig.from(properties, Map.of());
        var run = new PlanningEvaluationSuite().run(config, Map.of());
        assertThat(run.cases()).extracting(PlanningEvaluationSuite.Case::condition)
                .containsExactly("oracle", "actual_m2");
        assertThat(run.cases()).extracting(PlanningEvaluationSuite.Case::status).containsExactly("completed", "completed");
        assertThat(run.cases().getFirst().selectedMemories()).hasSize(1);
        assertThat(run.cases().get(1).memoryReplay()).isNotNull();
        assertThat(run.metadata().semanticGrading()).contains("not measured");
    }

    @Test
    @EnabledIfSystemProperty(named = "eval.suite", matches = "planning")
    void runSelectedPlanningEvaluationAndWriteReports() throws Exception {
        var config = EvaluationConfig.from(System.getProperties(), System.getenv());
        var run = new PlanningEvaluationSuite().run(config, System.getenv());
        var report = new EvaluationReportWriter().writePlanning(run, Path.of("target", "evals"),
                Arrays.asList(System.getenv("GEMINI_API_KEY"), System.getenv("EVAL_JUDGE_API_KEY")));
        System.out.println("Planning evaluation report: " + report.toAbsolutePath());
        assertThat(run.cases()).isNotEmpty();
        assertThat(run.cases().stream().filter(item -> !"completed".equals(item.status())).toList())
                .as("incomplete evaluations are recorded in " + report).isEmpty();
    }
}
