package com.chomiii.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MemoryEvaluationRunnerTest {
    private final FixtureLoader fixtures = new FixtureLoader();

    @Test
    void observesScriptedExtractionValidationPersistenceAndRecall() throws Exception {
        var fixture = fixtures.load(Path.of("..", "evals", "fixtures", "m2", "explicit-facts", "appointments.json"));
        var result = new MemoryEvaluationRunner().runOffline(fixture);

        assertThat(result.mode()).isEqualTo("offline-scripted");
        assertThat(result.turns().getFirst().observedEligibility()).isEqualTo("eligible");
        assertThat(result.turns().getFirst().extractionAttempted()).isTrue();
        assertThat(result.turns().getFirst().rawCandidates()).isEqualTo(1);
        assertThat(result.turns().getFirst().acceptedCandidates()).isEqualTo(1);
        assertThat(result.turns().getFirst().stored()).isEqualTo(1);
        assertThat(result.memories()).singleElement().satisfies(memory -> {
            assertThat(memory.content()).isEqualTo("I prefer appointments after ten in the morning");
            assertThat(memory.source()).isEqualTo("conversation_fact");
            assertThat(memory.status()).isEqualTo("active");
        });
        assertThat(result.retrievals()).singleElement().satisfies(retrieval ->
                assertThat(retrieval.selectedMemoryIds()).containsExactly(result.memories().getFirst().id()));
        assertThat(result.score().truePositives()).isEqualTo(1);
    }

    @Test
    void reportsPreExtractionSkipRatherThanModelFailure() throws Exception {
        var fixture = fixtures.load(Path.of("..", "evals", "fixtures", "m2", "negative", "generic-question.json"));
        var result = new MemoryEvaluationRunner().runOffline(fixture);
        assertThat(result.turns().getFirst().observedEligibility()).isEqualTo("no_personal_language");
        assertThat(result.turns().getFirst().extractionAttempted()).isFalse();
        assertThat(result.memories()).isEmpty();
        assertThat(result.score().precision()).isNull();
    }
}
