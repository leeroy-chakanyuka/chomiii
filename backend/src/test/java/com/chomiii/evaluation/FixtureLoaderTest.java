package com.chomiii.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixtureLoaderTest {
    @TempDir Path directory;

    private static final String VALID = """
            {"id":"sample-1","version":1,"description":"An appointment preference",
             "category":"explicit-facts","difficulty":"easy","inputType":"single_message",
             "synthetic":true,"turns":[{"id":"t1","role":"user","content":"I prefer appointments after ten.",
               "expectedEligibility":"eligible"}],
             "expectedMemories":[{"id":"f1","statement":"I prefer appointments after ten",
               "aliases":["Appointments after ten"],"sourceTurnIds":["t1"],"kind":"explicit_fact",
               "importance":"high","expectedStatus":"active","inScope":true}],
             "forbiddenMemories":[],"retrievalChecks":[{"query":"When are my appointments?",
               "expectedMemoryIds":["f1"]}],"lifecycle":[]}
            """;

    @Test
    void acceptsVersionedSyntheticFixtureWithLinkedFacts() throws Exception {
        EvaluationFixture fixture = load(VALID);
        assertThat(fixture.id()).isEqualTo("sample-1");
        assertThat(fixture.expectedMemories().getFirst().sourceTurnIds()).containsExactly("t1");
    }

    @Test
    void loadsCommittedSampleFixtures() throws Exception {
        var fixtures = new FixtureLoader().loadAll(Path.of("..", "evals", "fixtures", "m2"));
        assertThat(fixtures).extracting(EvaluationFixture::id)
                .contains("m2-explicit-appointments-001", "m2-negative-generic-001");
    }

    @Test
    void rejectsFactWhoseSourceTurnDoesNotExist() {
        assertThatThrownBy(() -> load(VALID.replace("\"sourceTurnIds\":[\"t1\"]",
                "\"sourceTurnIds\":[\"missing\"]")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source turn");
    }

    @Test
    void rejectsNonSyntheticAndUnsupportedVersion() {
        assertThatThrownBy(() -> load(VALID.replace("\"synthetic\":true", "\"synthetic\":false")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("synthetic");
        assertThatThrownBy(() -> load(VALID.replace("\"version\":1", "\"version\":2")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("version");
    }

    @Test
    void rejectsUnknownRetrievalFact() {
        assertThatThrownBy(() -> load(VALID.replace("\"expectedMemoryIds\":[\"f1\"]",
                "\"expectedMemoryIds\":[\"missing\"]")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retrieval");
    }

    @Test
    void rejectsMissingEligibilityAndCapacityLabel() {
        assertThatThrownBy(() -> load(VALID.replace("\"expectedEligibility\":\"eligible\"", "\"expectedEligibility\":null")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("eligibility");
        assertThatThrownBy(() -> load(VALID.replace("\"inScope\":true", "\"inScope\":null")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("capacity");
    }

    private EvaluationFixture load(String content) throws Exception {
        Path fixture = directory.resolve("fixture.json");
        Files.writeString(fixture, content);
        return new FixtureLoader().load(fixture);
    }
}
