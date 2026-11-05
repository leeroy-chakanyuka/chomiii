package com.chomiii.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DatasetExecutionTest {
    @Test
    void replaysEverySyntheticScenarioWithoutMistakingSkipsForModelCalls() throws Exception {
        var fixtures = new FixtureLoader().loadAll(Path.of("..", "evals", "fixtures", "m2"));
        var runner = new MemoryEvaluationRunner();
        for (var fixture : fixtures) {
            var result = runner.runOffline(fixture);
            for (var turn : result.turns()) {
                assertThat(turn.observedEligibility()).as(fixture.id() + "/" + turn.turnId())
                        .isEqualTo(turn.expectedEligibility());
                if (!"eligible".equals(turn.observedEligibility())) {
                    assertThat(turn.extractionAttempted()).as(fixture.id()).isFalse();
                }
            }
            assertThat(result.lifecycle()).as(fixture.id()).noneMatch(action -> "not_exercised".equals(action.outcome()));
        }
    }
}
