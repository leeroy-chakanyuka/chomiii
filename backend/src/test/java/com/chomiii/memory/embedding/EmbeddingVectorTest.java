package com.chomiii.memory.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EmbeddingVectorTest {
    @Test
    void normalizesAndRoundTripsPortableVectors() {
        float[] values = new float[768];
        values[0] = 3;
        values[1] = 4;

        float[] normalized = EmbeddingVector.normalized(values, 768);
        float[] restored = EmbeddingVector.decode(EmbeddingVector.encode(normalized), 768);

        assertThat(restored[0]).isCloseTo(0.6f, org.assertj.core.data.Offset.offset(0.00001f));
        assertThat(restored[1]).isCloseTo(0.8f, org.assertj.core.data.Offset.offset(0.00001f));
        assertThat(EmbeddingVector.dot(normalized, restored)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.00001));
    }

    @Test
    void rejectsWrongDimensionsZeroNormAndNonFiniteValues() {
        assertThatThrownBy(() -> EmbeddingVector.normalized(new float[5], 768)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmbeddingVector.normalized(new float[768], 768)).isInstanceOf(IllegalArgumentException.class);
        float[] invalid = new float[768];
        invalid[0] = Float.NaN;
        assertThatThrownBy(() -> EmbeddingVector.normalized(invalid, 768)).isInstanceOf(IllegalArgumentException.class);
    }
}
