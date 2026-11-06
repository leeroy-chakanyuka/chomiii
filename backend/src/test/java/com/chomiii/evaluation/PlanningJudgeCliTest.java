package com.chomiii.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "eval.suite", matches = "planning-judge")
class PlanningJudgeCliTest {
    @Test
    void judgeAStoredRunWithoutCallingThePlannerAgain() throws Exception {
        if (!"true".equals(System.getenv("EVAL_JUDGE"))) throw new IllegalArgumentException("Set EVAL_JUDGE=true");
        String source = System.getProperty("eval.sourceRun");
        if (source == null) throw new IllegalArgumentException("A source run is required");
        var config = EvaluationConfig.from(System.getProperties(), System.getenv());
        var judgeConfig = IndependentJudge.Config.from(System.getenv(), config.timeoutSeconds());
        var budget = new LiveBudget(true, config.maxCalls(), config.minProviderIntervalMillis(), config.maxProviderInputBytes());
        var judge = new IndependentJudge(judgeConfig, System.getenv("EVAL_JUDGE_API_KEY"), budget);
        var json = new ObjectMapper();
        var root = Path.of(source);
        var metadata = json.readValue(root.resolve("run-metadata.json").toFile(), PlanningEvaluationSuite.Metadata.class);
        var cases = json.readValue(root.resolve("results.json").toFile(),
                new TypeReference<java.util.List<PlanningEvaluationSuite.Case>>() {});
        var decisions = new ArrayList<PlanningReview.Decision>();
        String status = "completed";
        for (var item : cases) {
            if (!"completed".equals(item.status())) continue;
            if (budget.exhausted()) { status = "budget_or_provider_stopped"; break; }
            try { decisions.add(judge.judgePlanning(item)); }
            catch (Exception failure) { status = "judge_failed_" + failure.getClass().getSimpleName(); break; }
        }
        var review = new PlanningReview.Review("declared " + judgeConfig.family() + "/" + judgeConfig.model(), true, decisions);
        var report = new EvaluationReportWriter().writePlanningJudgeReview(new PlanningEvaluationSuite.Run(metadata, cases),
                review, Path.of("target", "evals"), Arrays.asList(System.getenv("GEMINI_API_KEY"),
                        System.getenv("EVAL_JUDGE_API_KEY")), judge.usage(), status);
        System.out.println("Planning judge report: " + report.toAbsolutePath() + " (" + status + ")");
        org.assertj.core.api.Assertions.assertThat(status).isEqualTo("completed");
    }
}
