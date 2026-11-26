package com.chomiii.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Replays the fictional persona through the existing evaluation harness in
 * offline (scripted) mode. This exercises the learning path (extraction per
 * turn, correction lifecycle edits, retrieval checks) that the seed-based tests
 * in {@link PersonaSeedOfflineTest} deliberately keep separate from seeded
 * recall. Scripted output is not a model-quality measurement.
 */
class PersonaReplayOfflineTest {

    @Test
    void personaFixtureReplaysWithLearningCorrectionsAndRecall() throws Exception {
        var fixture = FictionalPersona.fixture();
        var result = new MemoryEvaluationRunner().runOffline(fixture);
        var score = new MemoryScorer().score(fixture,
                MemoryEvaluationRunner.scoringMemories(result.memories(), result.turns()));

        assertThat(result.turns()).hasSize(fixture.turns().size()).allSatisfy(turn -> {
            assertThat(turn.failure()).as(turn.turnId()).isNull();
            assertThat(turn.observedEligibility()).isEqualTo(turn.expectedEligibility());
            assertThat(turn.validationOutcome()).as(turn.turnId())
                    .isIn("accepted_or_empty", "not_applicable");
        });
        assertThat(result.lifecycle()).hasSize(FictionalPersona.corrections().size())
                .allSatisfy(action -> assertThat(action.outcome()).isEqualTo("passed"));
        assertThat(result.retrievals()).allSatisfy(retrieval -> {
            assertThat(retrieval.failure()).as(retrieval.id()).isNull();
            var expected = score.matches().stream()
                    .filter(match -> retrieval.expectedMemoryIds().contains(match.expectedId()))
                    .map(MemoryScorer.Match::actualId).toList();
            assertThat(expected).hasSameSizeAs(retrieval.expectedMemoryIds());
            assertThat(retrieval.selectedMemoryIds()).containsAll(expected);
        });

        for (var fact : FictionalPersona.durableAndGoals()) {
            assertThat(result.memories()).anySatisfy(memory -> {
                assertThat(memory.content()).isEqualTo(fact.statement());
                assertThat(memory.status()).as(fact.id()).isEqualTo("active");
            });
        }
        for (var correction : FictionalPersona.corrections()) {
            assertThat(result.memories()).anySatisfy(memory -> {
                assertThat(memory.content()).isEqualTo(correction.corrected());
                assertThat(memory.source()).as(correction.id()).isEqualTo("user_explicit");
            });
            assertThat(result.memories()).noneMatch(memory -> memory.content().equals(correction.outdated()));
        }
        for (var ambiguity : FictionalPersona.ambiguous()) {
            assertThat(result.memories()).anySatisfy(memory -> {
                assertThat(memory.content()).isEqualTo(ambiguity.statement());
                assertThat(memory.status()).as(ambiguity.id()).isEqualTo("suggested");
                assertThat(memory.source()).isEqualTo("inferred");
            });
        }
        for (var distractor : FictionalPersona.distractors()) {
            assertThat(result.memories()).noneMatch(memory -> memory.content().equals(distractor.statement()));
        }

        assertThat(score.falsePositives()).isZero();
        assertThat(score.falseNegatives()).isZero();
        assertThat(score.unreviewed()).isZero();
        assertThat(score.precision()).isEqualTo(1.0);
        assertThat(score.recall()).isEqualTo(1.0);
        assertThat(result.limitations()).contains("Scripted output is not a model-quality measurement.");
    }
}
