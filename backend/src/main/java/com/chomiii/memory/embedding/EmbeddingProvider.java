package com.chomiii.memory.embedding;

public interface EmbeddingProvider {
    record Descriptor(String model, int dimensions, String configuration) {}

    Descriptor descriptor();
    float[] document(String content, String apiKey);
    float[] query(String content, String apiKey);
}
