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

    @Test
    void aDeliberatelyDeletedFactIsNotCountedAsMissing() {
        var deleted = new EvaluationFixture.ExpectedMemory("f1", "I prefer appointments after ten", List.of(),
                List.of("t1"), "explicit_fact", "high", "absent", true);
        var score = new MemoryScorer().score(fixture(List.of(deleted), List.of()), List.of());
        assertThat(score.falseNegatives()).isZero();
        var restored = new MemoryScorer().score(fixture(List.of(deleted), List.of()),
                List.of(new MemoryScorer.Actual("m1", deleted.statement(), "active")));
        assertThat(restored.falsePositives()).isEqualTo(1);
    }

    @Test
    void recordsIndependentSemanticMatchesAndDisagreements() {
        var fixture = fixture(List.of(fact("f1", "I prefer appointments after ten", true)), List.of());
        var actual = List.of(new MemoryScorer.Actual("m1", "The user likes late-morning appointments", "active"));
        var judgment = new JudgeAdapter.Judgment("sample", "other-model", "other-family",
                List.of(new JudgeAdapter.Match("f1", "m1", "I prefer appointments after ten", "Equivalent preference")),
                List.of(), List.of(), List.of(), List.of(), "Supported");
        var score = new MemoryScorer().score(fixture, actual, judgment);
        assertThat(score.truePositives()).isEqualTo(1);
        assertThat(score.matches().getFirst().method()).isEqualTo("independent_judge");
        assertThat(score.matches().getFirst().evidence()).isEqualTo("I prefer appointments after ten");
        var human = new JudgeAdapter.Judgment("sample", "human", "manual", judgment.matches(),
                List.of(), List.of(), List.of(), List.of(), "Reviewed");
        assertThat(new MemoryScorer().score(fixture, actual, human).matches().getFirst().method())
                .isEqualTo("human_confirmed");

        var disputed = new JudgeAdapter.Judgment("sample", "other-model", "other-family", List.of(),
                List.of(new JudgeAdapter.Issue("m2", "unsupported", "Questionable")), List.of(), List.of(), List.of(), "Disputed");
        var disagreement = new MemoryScorer().score(fixture,
                List.of(new MemoryScorer.Actual("m2", "I prefer appointments after ten", "active")), disputed);
        assertThat(disagreement.disagreements()).containsExactly("m2");
        assertThat(disagreement.precision()).isNull();
    }

    @Test
    void distinguishesSuggestedInferencesFromLowConfidenceFacts() {
        var inferred = new EvaluationFixture.ExpectedMemory("f1", "I might prefer early walks", List.of(),
                List.of("t1"), "inference", "medium", "suggested", true);
        var score = new MemoryScorer().score(fixture(List.of(inferred), List.of()),
                List.of(new MemoryScorer.Actual("m1", inferred.statement(), "suggested", null, "conversation_fact")));
        assertThat(score.statusCorrect()).isEqualTo(1);
        assertThat(score.kindCorrect()).isZero();
        assertThat(score.kindAccuracy()).isEqualTo(0.0);
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
