package com.chomiii.memory.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class GeminiEmbeddingProviderTest {
    @Test
    void sendsDocumentAndQueryPrefixesAndRequests768Dimensions() throws Exception {
        var requests = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String values = IntStream.range(0, 768).mapToObj(i -> i == 0 ? "1.0" : "0.0")
                    .collect(Collectors.joining(","));
            byte[] response = ("{\"embeddings\":[{\"values\":[" + values + "]}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var out = exchange.getResponseBody()) { out.write(response); }
        });
        server.start();
        try {
            var provider = new GeminiEmbeddingProvider("gemini-embedding-2",
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            assertThat(provider.document("I like tacos", "test-key")).hasSize(768);
            assertThat(provider.query("dinner", "test-key")).hasSize(768);
            assertThat(requests).hasSize(2);
            assertThat(requests.get(0)).contains("title: none | text: I like tacos", "768").doesNotContain("taskType");
            assertThat(requests.get(1)).contains("task: search result | query: dinner", "768").doesNotContain("taskType");
            assertThat(requests.toString()).doesNotContain("test-key");
        } finally {
            server.stop(0);
        }
    }
}
