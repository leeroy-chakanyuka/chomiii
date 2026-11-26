package com.chomiii.memory.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "CHOMIII_RUN_EMBEDDING_SMOKE", matches = "true")
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GeminiEmbeddingLiveTest {
    @Test
    void returnsOneNormalized768DimensionalEmbedding() {
        var provider = new GeminiEmbeddingProvider("gemini-embedding-2");
        float[] vector = provider.document("A fictional person enjoys tacos for dinner.", System.getenv("GEMINI_API_KEY"));
        assertThat(vector).hasSize(768);
        assertThat(EmbeddingVector.dot(vector, vector)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.0001));
    }
}
