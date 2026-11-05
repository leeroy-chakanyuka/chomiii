package com.chomiii.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class JudgeResponseParserTest {
    private static final String RESPONSE = """
            {"fixtureId":"m2-explicit-appointments-001","matches":[{"expectedId":"appointment-time",
            "actualId":"m1","verdict":"supported","evidence":"I prefer appointments after ten",
            "reason":"The user stated a preference."}],"unsupported":[],"missingExpected":[],
            "contradictions":[],"wrongPersonAttributions":[],"overInferences":[],"needsHumanReview":[],"answers":[],
            "summary":"The memory is supported."}
            """;

    @Test
    void validatesReferencesAndSourceEvidenceBeforeAcceptingAMatch() throws Exception {
        var fixture = new FixtureLoader().load(Path.of("..", "evals", "fixtures", "m2", "explicit-facts", "appointments.json"));
        var actual = List.of(new MemoryEvaluationRunner.StoredMemory("m1", "Appointments after ten", "conversation_fact", "active", 0.95, null));
        var parser = new JudgeResponseParser();
        assertThat(parser.parse(RESPONSE, fixture, actual, List.of(), "judge-model", "other-family").matches()).hasSize(1);
        assertThatThrownBy(() -> parser.parse(RESPONSE.replace("I prefer appointments after ten", "invented evidence"),
                fixture, actual, List.of(), "judge-model", "other-family"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("evidence");
        assertThatThrownBy(() -> parser.parse(RESPONSE.replace("\"actualId\":\"m1\"", "\"actualId\":\"unknown\""),
                fixture, actual, List.of(), "judge-model", "other-family"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("match");
        assertThatThrownBy(() -> parser.parse("{bad json", fixture, actual, List.of(), "judge-model", "other-family"))
                .isInstanceOf(Exception.class);
    }

    @Test
    void positiveAnswerVerdictMustQuoteASelectedMemory() throws Exception {
        var fixture = new FixtureLoader().load(Path.of("..", "evals", "fixtures", "m2", "explicit-facts", "appointments.json"));
        var actual = List.of(new MemoryEvaluationRunner.StoredMemory("m1", "Appointments after ten", "conversation_fact", "active", 0.95, null));
        var retrieval = new MemoryEvaluationRunner.RetrievalObservation("r1", "When?", List.of("m1"),
                List.of("appointment-time"), 1, "After ten", true, false, List.of(), false, null);
        String answer = "\"answers\":[{\"retrievalId\":\"r1\",\"verdict\":\"correct\","
                + "\"evidence\":\"after ten\",\"reason\":\"Matches the selected memory\"}]";
        var parser = new JudgeResponseParser();
        var result = parser.parse(RESPONSE.replace("\"answers\":[]", answer), fixture, actual,
                List.of(retrieval), "other-model", "other-family");
        assertThat(result.answers()).hasSize(1);
        assertThatThrownBy(() -> parser.parse(RESPONSE.replace("\"answers\":[]", answer.replace("after ten", "invented")),
                fixture, actual, List.of(retrieval), "other-model", "other-family"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("selected memory");
    }
}
