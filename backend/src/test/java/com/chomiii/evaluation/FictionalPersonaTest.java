package com.chomiii.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.chomiii.memory.MemoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Shape, determinism and harness-compatibility checks for the fictional persona. */
class FictionalPersonaTest {
    @TempDir
    Path directory;

    @Test
    void statementsAreDeterministicWellFormedAndUnique() {
        var statements = new HashSet<String>();
        var ids = new HashSet<String>();
        for (var fact : FictionalPersona.durableAndGoals()) {
            check(fact.id(), fact.statement(), ids, statements);
        }
        for (var correction : FictionalPersona.corrections()) {
            check(correction.id() + "-outdated", correction.outdated(), ids, statements);
            check(correction.id() + "-corrected", correction.corrected(), ids, statements);
            check(correction.id() + "-phrase", correction.correctionPhrase(), ids, statements);
            assertThat(correction.corrected()).isNotEqualTo(correction.outdated());
        }
        for (var ambiguity : FictionalPersona.ambiguous()) {
            check(ambiguity.id(), ambiguity.statement(), ids, statements);
            assertThat(ambiguity.missingInformation()).isNotBlank();
        }
        int distractor = 0;
        for (var item : FictionalPersona.distractors()) {
            check("distractor-" + distractor++, item.statement(), ids, statements);
            assertThat(item.reason()).isNotBlank();
            assertThat(item.category()).isNotBlank();
        }
        assertThat(FictionalPersona.durableAndGoals()).isEqualTo(FictionalPersona.durableAndGoals());
    }

    private static void check(String id, String statement, HashSet<String> ids, HashSet<String> statements) {
        assertThat(ids.add(id)).as("duplicate id " + id).isTrue();
        assertThat(statements.add(statement)).as("duplicate statement: " + statement).isTrue();
        assertThat(statement.length()).as(id).isBetween(8, 500);
    }

    /**
     * Changed facts conflict under the product's learned-fact gate: a corrected
     * statement learned while the outdated fact is still active stays a
     * suggestion, and every plain durable fact passes the gate unchanged.
     */
    @Test
    void changedFactsAreGatedWhilePlainFactsAreNot() {
        var memories = new MemoryService(new InMemoryEvaluationRepository());
        for (var correction : FictionalPersona.corrections()) {
            String owner = "gated-" + correction.id();
            PersonaSeed.fact(memories, owner, correction.outdatedFact());
            memories.learn(owner, UUID.randomUUID(), memories.revision(owner),
                    correction.corrected(), "conversation_fact", 0.95);
            assertThat(memories.list(owner)).anySatisfy(memory -> {
                assertThat(memory.content()).isEqualTo(correction.corrected());
                assertThat(memory.status()).as(correction.id()).isEqualTo("suggested");
            });
        }
        String owner = "plain";
        var seeded = PersonaSeed.durable(memories, owner);
        long revision = memories.revision(owner);
        for (var fact : FictionalPersona.durableAndGoals()) {
            memories.learn(owner, UUID.randomUUID(), revision, "I also keep " + fact.id()
                    + " notes in a notebook.", "conversation_fact", 0.95);
        }
        assertThat(memories.list(owner))
                .filteredOn(memory -> memory.content().startsWith("I also keep"))
                .allSatisfy(memory -> assertThat(memory.status()).isEqualTo("active"));
        assertThat(seeded).hasSameSizeAs(FictionalPersona.durableAndGoals());
    }

    @Test
    void fixturePassesTheExistingLoaderAndPlanningContract() throws Exception {
        var fixture = FictionalPersona.fixture();
        var file = directory.resolve("persona.json");
        new ObjectMapper().writeValue(file.toFile(), fixture);
        var loaded = new FixtureLoader().loadAll(directory);
        assertThat(loaded).hasSize(1);
        assertThat(loaded.getFirst().id()).isEqualTo(FictionalPersona.ID);
        var facts = fixture.expectedMemories().stream()
                .map(EvaluationFixture.ExpectedMemory::id).collect(java.util.stream.Collectors.toSet());
        assertThat(fixture.retrievalChecks()).allSatisfy(check ->
                assertThat(facts).containsAll(check.expectedMemoryIds()));
        PlanningContract.validate(fixture.planning(), facts);
        assertThat(fixture.turns()).allSatisfy(turn -> assertThat(turn.role()).isEqualTo("user"));
        assertThat(fixture.forbiddenMemories()).extracting(EvaluationFixture.ForbiddenMemory::statement)
                .containsExactlyInAnyOrderElementsOf(FictionalPersona.distractors().stream()
                        .map(FictionalPersona.Distractor::statement).toList());
    }

    @Test
    void distractorsArePenalizedByTheScorerWhenStored() {
        var fixture = FictionalPersona.fixture();
        var stored = List.of(new MemoryScorer.Actual("distractor-1",
                FictionalPersona.distractors().getFirst().statement(), "active", null,
                "conversation_fact"));
        var score = new MemoryScorer().score(fixture, stored);
        assertThat(score.falsePositives()).isEqualTo(1);
        assertThat(score.classifiedFalsePositives()).extracting(
                MemoryScorer.FalsePositive::category).containsExactly("wrong_person");
    }
}
