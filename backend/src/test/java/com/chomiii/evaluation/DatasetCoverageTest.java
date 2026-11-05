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
        assertThat(fixtures.size()).isGreaterThanOrEqualTo(30);
        Map<String, Long> counts = fixtures.stream().map(EvaluationFixture::category)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        for (String category : new String[]{"explicit-facts", "long-messages", "conversations", "corrections", "inferences", "negative"}) {
            assertThat(counts.getOrDefault(category, 0L)).as(category).isGreaterThanOrEqualTo(4);
        }
        assertThat(counts.getOrDefault("lifecycle", 0L)).isGreaterThanOrEqualTo(6);
        assertThat(fixtures).flatExtracting(EvaluationFixture::turns)
                .extracting(turn -> turn.content().length()).contains(500, 1000, 1800, 2000, 2001, 8100);
        assertThat(fixtures).allMatch(fixture -> Boolean.TRUE.equals(fixture.synthetic()));
    }
}
