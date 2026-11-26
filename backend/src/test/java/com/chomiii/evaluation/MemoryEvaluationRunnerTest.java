package com.chomiii.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class MemoryEvaluationRunnerTest {
    private final FixtureLoader fixtures = new FixtureLoader();

    @Test
    void olderReportsWithoutTokenUsageStillLoad() throws Exception {
        var result = new ObjectMapper().readValue("""
                {"fixtureId":"old","mode":"offline","turns":[],"memories":[],"retrievals":[],
                 "lifecycle":[],"extractionScore":null,"score":null,"limitations":[]}
                """, MemoryEvaluationRunner.Result.class);
        assertThat(result.providerUsage()).isEmpty();
    }

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
        assertThat(result.retrievals().getFirst().contextAsUser()).isTrue();
        assertThat(result.retrievals().getFirst().memoryInSystem()).isFalse();
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

    @Test
    void deletingARecordDuringExtractionDoesNotResurrectIt() throws Exception {
        var json = new ObjectMapper();
        String first = "I usually take the train to work.";
        String second = "I usually take the train to work every weekday.";
        var one = json.readTree("""
                {"memories":[{"content":"I usually take the train to work","kind":"fact",
                "confidence":0.95,"evidence":"I usually take the train to work"}]}
                """);
        var two = json.readTree("""
                {"memories":[{"content":"I usually take the train to work","kind":"fact",
                "confidence":0.95,"evidence":"I usually take the train to work"}]}
                """);
        var fact = new EvaluationFixture.ExpectedMemory("commute", "I usually take the train to work", List.of(),
                List.of("t1", "t2"), "explicit_fact", "high", "absent", true);
        var fixture = new EvaluationFixture("race", 1, "deletion race", "lifecycle", "difficult", "conversation", true,
                List.of(new EvaluationFixture.Turn("t1", "user", first, "eligible", one),
                        new EvaluationFixture.Turn("t2", "user", second, "eligible", two)),
                List.of(fact), List.of(), List.of(),
                List.of(new EvaluationFixture.LifecycleAction("t2", "delete_while_pending", "commute", null)), null);

        var result = new MemoryEvaluationRunner().runOffline(fixture);
        assertThat(result.turns().get(1).acceptedCandidates()).isEqualTo(1);
        assertThat(result.turns().get(1).stored()).isZero();
        assertThat(result.lifecycle()).singleElement().satisfies(action -> assertThat(action.outcome()).isEqualTo("passed"));
        assertThat(result.memories()).isEmpty();
    }

    @Test
    void unapprovedSuggestionIsNotRecalledAndAnotherOwnerSeesNothing() throws Exception {
        var json = new ObjectMapper();
        var response = json.readTree("""
                {"memories":[{"content":"I might prefer Saturday shopping","kind":"inference",
                "confidence":0.75,"evidence":"I might prefer Saturday shopping"}]}
                """);
        var fact = new EvaluationFixture.ExpectedMemory("shopping", "I might prefer Saturday shopping", List.of(),
                List.of("t1"), "inference", "medium", "suggested", true);
        var fixture = new EvaluationFixture("suggestion", 1, "suggestion", "lifecycle", "medium", "single_message", true,
                List.of(new EvaluationFixture.Turn("t1", "user", "I might prefer Saturday shopping.", "eligible", response)),
                List.of(fact), List.of(), List.of(new EvaluationFixture.RetrievalCheck("When is my shopping?", List.of())),
                List.of(new EvaluationFixture.LifecycleAction("t1", "other_owner", null, null)), null);

        var result = new MemoryEvaluationRunner().runOffline(fixture);
        assertThat(result.memories()).singleElement().satisfies(memory -> assertThat(memory.status()).isEqualTo("suggested"));
        assertThat(result.retrievals().getFirst().selectedMemoryIds()).isEmpty();
        assertThat(result.lifecycle().getFirst().outcome()).isEqualTo("passed");
    }

    @Test
    void malformedExtractionIsReportedRatherThanTreatedAsAnEmptySuccess() throws Exception {
        var fixture = new EvaluationFixture("malformed", 1, "malformed output", "negative", "medium", "single_message", true,
                List.of(new EvaluationFixture.Turn("t1", "user", "I usually take the train to work.", "eligible",
                        com.fasterxml.jackson.databind.node.TextNode.valueOf("{broken"))),
                List.of(), List.of(), List.of(), List.of(), null);
        var result = new MemoryEvaluationRunner().runOffline(fixture);
        assertThat(result.turns().getFirst().validationOutcome()).isEqualTo("invalid_json");
        assertThat(result.memories()).isEmpty();
    }
}
