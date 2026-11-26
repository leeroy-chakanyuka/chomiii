package com.chomiii.memory.embedding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class EmbeddingVector {
    private static final ObjectMapper JSON = new ObjectMapper();

    private EmbeddingVector() {}

    public static float[] normalized(float[] values, int dimensions) {
        if (values == null || values.length != dimensions) throw new IllegalArgumentException("Invalid embedding dimensions");
        double sum = 0;
        for (float value : values) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Invalid embedding value");
            sum += (double) value * value;
        }
        if (!Double.isFinite(sum) || sum == 0) throw new IllegalArgumentException("Invalid embedding norm");
        double norm = Math.sqrt(sum);
        float[] result = new float[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (float) (values[i] / norm);
        return result;
    }

    public static double dot(float[] left, float[] right) {
        if (left.length != right.length) throw new IllegalArgumentException("Incompatible embeddings");
        double result = 0;
        for (int i = 0; i < left.length; i++) result += (double) left[i] * right[i];
        return result;
    }

    public static String encode(float[] values) {
        try {
            return JSON.writeValueAsString(values);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Invalid embedding", invalid);
        }
    }

    public static float[] decode(String value, int dimensions) {
        try {
            return normalized(JSON.readValue(value, float[].class), dimensions);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Invalid stored embedding", invalid);
        }
    }
}
