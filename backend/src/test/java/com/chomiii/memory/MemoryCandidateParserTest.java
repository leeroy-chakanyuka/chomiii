package com.chomiii.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryCandidateParserTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void acceptsGroundedFactButKeepsInferencesDistinct() {
        String text = "My usual groceries are oats and milk. I sometimes shop on Thursdays.";
        String output = """
                {"memories":[
                  {"content":"My usual groceries are oats and milk",
                   "kind":"fact","confidence":0.98,"evidence":"My usual groceries are oats and milk"},
                  {"content":"I may shop on Thursdays",
                   "kind":"inference","confidence":0.75,"evidence":"I sometimes shop on Thursdays"}
                ]}
                """;
        List<MemoryCandidateParser.Candidate> result =
                MemoryCandidateParser.parse(output, text, json);
        assertThat(result).hasSize(2);
        assertThat(result.get(0).source()).isEqualTo("conversation_fact");
        assertThat(result.get(0).topic()).isNull();
        assertThat(result.get(1).source()).isEqualTo("inferred");
    }

    @Test
    void optionalSlotTopicsAreSanitizedOrDropped() {
        String text = "We moved to Bulawayo last month.";
        String good = """
                {"memories":[{"content":"I moved to Bulawayo","kind":"fact","confidence":0.95,
                "evidence":"moved to Bulawayo","topic":"residence"}]}
                """;
        List<MemoryCandidateParser.Candidate> parsed = MemoryCandidateParser.parse(good, text, json);
        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).topic()).isEqualTo("residence");

        // An unusable topic never rejects the grounded candidate; it is dropped.
        String sensitive = """
                {"memories":[
                  {"content":"I moved to Bulawayo","kind":"fact","confidence":0.95,
                   "evidence":"moved to Bulawayo","topic":"religious beliefs"},
                  {"content":"I moved to Bulawayo","kind":"fact","confidence":0.95,
                   "evidence":"moved to Bulawayo"}
                ]}
                """;
        List<MemoryCandidateParser.Candidate> dropped =
                MemoryCandidateParser.parse(sensitive, text, json);
        assertThat(dropped).hasSize(2);
        assertThat(dropped).allSatisfy(candidate -> assertThat(candidate.topic()).isNull());

        String unusable = """
                {"memories":[
                  {"content":"I moved to Bulawayo","kind":"fact","confidence":0.95,
                   "evidence":"moved to Bulawayo","topic":"%s"},
                  {"content":"I moved to Bulawayo","kind":"fact","confidence":0.95,
                   "evidence":"moved to Bulawayo","topic":"   "}
                ]}
                """.formatted("x".repeat(61));
        List<MemoryCandidateParser.Candidate> sanitized =
                MemoryCandidateParser.parse(unusable, text, json);
        assertThat(sanitized).hasSize(2);
        assertThat(sanitized).allSatisfy(candidate -> assertThat(candidate.topic()).isNull());
    }

    @Test
    void rejectsUngroundedFactsSensitiveMessagesAndInsufficientConfidence() {
        String text = "My usual groceries are oats and milk.";
        String hallucination = """
                {"memories":[{"content":"I own a yacht","kind":"fact","confidence":0.98,
                "evidence":"I own a yacht"}]}
                """;
        assertThat(MemoryCandidateParser.parse(hallucination, text, json)).isEmpty();
        assertThat(MemoryCandidateParser.parse(hallucination, "My bank account is secret", json)).isEmpty();
        String lowConfidence = """
                {"memories":[{"content":"My usual groceries are oats and milk",
                "kind":"inference","confidence":0.2,
                "evidence":"My usual groceries are oats and milk"}]}
                """;
        assertThat(MemoryCandidateParser.parse(lowConfidence, text, json)).isEmpty();
    }

    @Test
    void refusesOversizedMalformedOrTooManySuggestions() {
        String message = "My usual groceries are oats and milk.";
        assertThat(MemoryCandidateParser.parse("{garbage", message, json)).isEmpty();
        assertThat(MemoryCandidateParser.parse("x".repeat(4001), message, json)).isEmpty();
        String entry = """
                {"content":"My usual groceries are oats and milk","kind":"fact",
                "confidence":0.95,"evidence":"My usual groceries are oats and milk"}""";
        assertThat(MemoryCandidateParser.parse(
                "{\"memories\":[" + entry + "," + entry + "," + entry + "]}", message, json)).isEmpty();
    }
}
