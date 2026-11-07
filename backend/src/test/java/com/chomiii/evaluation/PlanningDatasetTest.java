package com.chomiii.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PlanningDatasetTest {
    @Test
    void loadsThirtyPlanningScenariosLinkedToTheExistingMemoryFixtures() throws Exception {
        var loader = new FixtureLoader();
        var memories = loader.loadAll(Path.of("..", "evals", "fixtures", "m2"));
        var cases = loader.loadPlanning(Path.of("..", "evals", "fixtures", "planning", "scenarios.json"), memories);
        assertThat(cases).hasSizeGreaterThanOrEqualTo(30);
        assertThat(cases.stream().filter(PlanningFixture::heldOut)).hasSize(6);
        var properties = new java.util.Properties();
        properties.setProperty("eval.mode", "live");
        var environment = Map.of("EVAL_LIVE", "true", "GEMINI_API_KEY", "fake", "EVAL_MAX_PROVIDER_CALLS", "1");
        assertThat(PlanningEvaluationSuite.select(cases, EvaluationConfig.from(properties, environment)))
                .noneMatch(PlanningFixture::heldOut);
        properties.setProperty("eval.fixture", "rambling-volunteer");
        assertThat(PlanningEvaluationSuite.select(cases, EvaluationConfig.from(properties, environment)))
                .singleElement().satisfies(item -> assertThat(item.heldOut()).isTrue());
        Map<String, Long> counts = cases.stream().collect(Collectors.groupingBy(PlanningFixture::category, Collectors.counting()));
        assertThat(counts).containsEntry("simple", 4L).containsEntry("rambling", 5L)
                .containsEntry("memory", 5L).containsEntry("ambiguous", 4L)
                .containsEntry("multi-goal", 4L).containsEntry("conflicting", 3L)
                .containsEntry("unavailable", 3L).containsEntry("safety", 2L);
    }
}
