package com.chomiii.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class DatasetCoverageTest {
    @Test
    void schemaIsValidJsonAndNamesTheRequiredFixtureFields() throws Exception {
        var schema = new ObjectMapper().readTree(Path.of("..", "evals", "schemas", "m2-fixture.schema.json").toFile());
        assertThat(schema.path("$schema").asText()).contains("json-schema.org");
        assertThat(schema.path("required").toString()).contains("synthetic", "turns", "expectedMemories", "lifecycle");
    }

    @Test
    void containsSyntheticCasesAcrossAllRequiredCategoriesAndLengthBoundaries() throws Exception {
        var fixtures = new FixtureLoader().loadAll(Path.of("..", "evals", "fixtures", "m2"));
        assertThat(fixtures.size()).isGreaterThanOrEqualTo(150);
        Map<String, Long> counts = fixtures.stream().map(EvaluationFixture::category)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        Map<String, Long> minimums = Map.of(
                "explicit-facts", 24L, "long-messages", 15L, "conversations", 24L,
                "corrections", 24L, "inferences", 16L, "negative", 26L, "lifecycle", 21L);
        minimums.forEach((category, minimum) -> assertThat(counts.getOrDefault(category, 0L))
                .as(category).isGreaterThanOrEqualTo(minimum));
        assertThat(fixtures).flatExtracting(EvaluationFixture::turns)
                .extracting(turn -> turn.content().length()).contains(500, 1000, 1800, 2000, 2001, 8100);
        assertThat(fixtures).allMatch(fixture -> Boolean.TRUE.equals(fixture.synthetic()));
        assertThat(fixtures.stream().filter(fixture -> fixture.id().startsWith("m2-adversarial-")).count())
                .isEqualTo(110);
        assertThat(fixtures.stream().filter(fixture -> fixture.turns().stream()
                .filter(turn -> "user".equals(turn.role())).count() >= 3).count())
                .isGreaterThanOrEqualTo(40);
        assertThat(fixtures.stream().filter(fixture -> fixture.expectedMemories().size() >= 3).count())
                .isGreaterThanOrEqualTo(20);
        assertThat(fixtures.stream().filter(fixture -> fixture.forbiddenMemories().stream()
                .anyMatch(memory -> "wrong_person".equals(memory.category()))).count())
                .isGreaterThanOrEqualTo(60);
    }
}
