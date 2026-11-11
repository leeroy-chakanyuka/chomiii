package com.chomiii.memory.embedding;

import com.google.genai.Client;
import com.google.genai.types.EmbedContentConfig;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class GeminiEmbeddingProvider implements EmbeddingProvider {
    private final Descriptor descriptor;
    private final URI endpoint;

    @Autowired
    public GeminiEmbeddingProvider(@Value("${chomiii.memory.embeddings.model:gemini-embedding-2}") String model) {
        this(model, URI.create("https://generativelanguage.googleapis.com"));
    }

    GeminiEmbeddingProvider(String model, URI endpoint) {
        this.descriptor = new Descriptor(model, 768, "search-v1");
        this.endpoint = endpoint;
    }

    @Override public Descriptor descriptor() { return descriptor; }
    @Override public float[] document(String content, String apiKey) {
        return embed("title: none | text: " + content, apiKey);
    }
    @Override public float[] query(String content, String apiKey) {
        return embed("task: search result | query: " + content, apiKey);
    }

    private float[] embed(String input, String apiKey) {
        var http = HttpOptions.builder().baseUrl(endpoint.toString()).timeout(10_000)
                .retryOptions(HttpRetryOptions.builder().attempts(1).build()).build();
        try (Client client = Client.builder().enterprise(false).apiKey(apiKey).httpOptions(http).build()) {
            var config = EmbedContentConfig.builder().outputDimensionality(descriptor.dimensions()).build();
            var response = client.models.embedContent(descriptor.model(), input, config);
            var embeddings = response.embeddings().orElse(List.of());
            if (embeddings.size() != 1) throw new IllegalArgumentException("Invalid embedding response");
            var values = embeddings.getFirst().values().orElse(List.of());
            if (values.size() != descriptor.dimensions()) throw new IllegalArgumentException("Invalid embedding dimensions");
            float[] result = new float[values.size()];
            for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
            return EmbeddingVector.normalized(result, descriptor.dimensions());
        }
    }
}
