package com.chomiii.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryScorerTest {
    @Test
    void matchesOneActualMemoryToOnlyOneExpectedFact() {
        var fixture = fixture(List.of(fact("f1", "I prefer appointments after ten", true),
                fact("f2", "I prefer cycling to work", false)),
                List.of(new EvaluationFixture.ForbiddenMemory("I always drink coffee", "unsupported", "over_inference")));
        var score = new MemoryScorer().score(fixture, List.of(
                new MemoryScorer.Actual("m1", "I prefer appointments after ten", "suggested"),
                new MemoryScorer.Actual("m2", "I prefer appointments after ten", "active"),
                new MemoryScorer.Actual("m3", "I always drink coffee", "active")));

        assertThat(score.truePositives()).isEqualTo(1);
        assertThat(score.falsePositives()).isEqualTo(2);
        assertThat(score.falseNegatives()).isEqualTo(1);
        assertThat(score.activeMatches()).isZero();
        assertThat(score.withinCapacityRecall()).isEqualTo(1.0);
        assertThat(score.recall()).isEqualTo(0.5);
        assertThat(score.precision()).isEqualTo(1.0 / 3.0);
        assertThat(score.matches()).hasSize(1);
    }

    @Test
    void acceptsHumanWrittenAliasesButDoesNotGuessUnknownSemantics() {
        var fixture = fixture(List.of(fact("f1", "I prefer appointments after ten", true)), List.of());
        var score = new MemoryScorer().score(fixture, List.of(
                new MemoryScorer.Actual("m1", "Appointments after ten", "active"),
                new MemoryScorer.Actual("m2", "I prefer afternoon appointments", "active")));
        assertThat(score.truePositives()).isEqualTo(1);
        assertThat(score.unreviewed()).isEqualTo(1);
        assertThat(score.matches().getFirst().method()).isEqualTo("human_alias");
        assertThat(score.precision()).isNull();
    }

    @Test
    void emptyDenominatorsAreNotReportedAsPerfectScores() {
        var score = new MemoryScorer().score(fixture(List.of(), List.of()), List.of());
        assertThat(score.precision()).isNull();
        assertThat(score.recall()).isNull();
        assertThat(score.f1()).isNull();
    }

    @Test
    void findsMaximumOneToOneMatchingWhenAliasesOverlap() {
        var first = new EvaluationFixture.ExpectedMemory("f1", "I prefer tea", List.of("I prefer drinks"),
                List.of("t1"), "explicit_fact", "high", "active", true);
        var second = new EvaluationFixture.ExpectedMemory("f2", "I prefer coffee", List.of("I prefer drinks"),
                List.of("t1"), "explicit_fact", "high", "active", true);
        var score = new MemoryScorer().score(fixture(List.of(first, second), List.of()), List.of(
                new MemoryScorer.Actual("m1", "I prefer drinks", "active"),
                new MemoryScorer.Actual("m2", "I prefer tea", "active")));
        assertThat(score.truePositives()).isEqualTo(2);
        assertThat(score.recall()).isEqualTo(1.0);
    }

    private static EvaluationFixture.ExpectedMemory fact(String id, String statement, boolean inScope) {
        return new EvaluationFixture.ExpectedMemory(id, statement, List.of("Appointments after ten"),
                List.of("t1"), "explicit_fact", "high", "active", inScope);
    }

    private static EvaluationFixture fixture(List<EvaluationFixture.ExpectedMemory> expected,
            List<EvaluationFixture.ForbiddenMemory> forbidden) {
        return new EvaluationFixture("sample", 1, "sample", "explicit-facts", "easy", "single_message", true,
                List.of(new EvaluationFixture.Turn("t1", "user", "I prefer appointments after ten", "eligible", null)),
                expected, forbidden, List.of(), List.of(), null);
    }
}
