package com.chomiii.provider.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chomiii.provider.ChatProvider;
import com.chomiii.provider.ProviderFailure;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

class GeminiProviderTest {
    private HttpServer server;
    private ExecutorService serverThreads;
    private final AtomicReference<String> credential = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> response = new AtomicReference<>(
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello\"}],\"role\":\"model\"}}]}\n\n"
            + "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\" world\"}],\"role\":\"model\"}}]}\n\n");
    private final AtomicBoolean waitForFirstChunk = new AtomicBoolean();
    private final AtomicBoolean firstChunkObserved = new AtomicBoolean();
    private final CountDownLatch firstChunk = new CountDownLatch(1);
    private final AtomicReference<CountDownLatch> concurrentRequests = new AtomicReference<>();
    private final Map<String, String> bodiesByKey = new ConcurrentHashMap<>();
    private final AtomicReference<CountDownLatch> releaseResponse = new AtomicReference<>();
    private final AtomicInteger responseDelayMillis = new AtomicInteger();
    private final AtomicInteger secondChunkDelayMillis = new AtomicInteger();
    private final AtomicReference<String> errorMessage = new AtomicReference<>("secret test-key-one");

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String requestKey = exchange.getRequestHeaders().getFirst("x-goog-api-key");
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            credential.set(requestKey);
            body.set(requestBody);
            bodiesByKey.put(requestKey, requestBody);
            path.set(exchange.getRequestURI().toString());
            if (responseDelayMillis.get() > 0) {
                try {
                    Thread.sleep(responseDelayMillis.get());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            CountDownLatch concurrent = concurrentRequests.get();
            if (concurrent != null) {
                concurrent.countDown();
                try {
                    concurrent.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            if (status.get() != 200) {
                byte[] error = ("{\"error\":{\"message\":\"" + errorMessage.get() + "\",\"status\":\"ERROR\"}}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status.get(), error.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(error);
                }
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                String content = response.get();
                if (waitForFirstChunk.get()) {
                    int split = content.indexOf("\n\n") + 2;
                    out.write(content.substring(0, split).getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    try {
                        firstChunkObserved.set(firstChunk.await(15, TimeUnit.SECONDS));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    CountDownLatch release = releaseResponse.get();
                    if (release != null) {
                        try {
                            release.await(20, TimeUnit.SECONDS);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    if (secondChunkDelayMillis.get() > 0) {
                        try {
                            Thread.sleep(secondChunkDelayMillis.get());
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    out.write(content.substring(split).getBytes(StandardCharsets.UTF_8));
                } else {
                    out.write(content.getBytes(StandardCharsets.UTF_8));
                }
            }
        });
        serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        serverThreads.shutdownNow();
    }

    @Test
    void streamsTextAndSendsTheRequestScopedKeyAndConversation() {
        waitForFirstChunk.set(true);
        var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        ChatProvider provider = new GeminiProvider(endpoint);
        var input = new ChatProvider.ChatInput("gemini-3.5-flash-lite", "Be helpful.", List.of(
                new ChatProvider.Message(ChatProvider.Role.USER, "My language is Java"),
                new ChatProvider.Message(ChatProvider.Role.ASSISTANT, "Noted"),
                new ChatProvider.Message(ChatProvider.Role.USER, "Which language?")));
        List<String> chunks = new ArrayList<>();

        try (var generation = provider.open(input, "test-key-one")) {
            generation.stream(chunk -> {
                chunks.add(chunk);
                firstChunk.countDown();
            });
        }

        assertThat(chunks).containsExactly("Hello", " world");
        assertThat(firstChunkObserved.get()).isTrue();
        assertThat(credential.get()).isEqualTo("test-key-one");
        assertThat(body.get()).contains("My language is Java", "Which language?", "Be helpful.", "model");
        assertThat(body.get()).doesNotContain("test-key-one");
        assertThat(path.get()).doesNotContain("test-key-one");
    }

    @ParameterizedTest
    @CsvSource({"400, INVALID_REQUEST", "401, INVALID_KEY", "403, INVALID_KEY", "404, UNSUPPORTED_MODEL",
            "408, TIMEOUT", "429, RATE_LIMITED", "500, UNAVAILABLE"})
    void translatesProviderErrorsWithoutReturningItsMessage(int httpStatus, ProviderFailure.Code code) {
        status.set(httpStatus);
        try (var generation = provider().open(input(), "test-key-one")) {
            assertThatThrownBy(() -> generation.stream(ignored -> {}))
                    .isInstanceOf(ProviderFailure.class)
                    .satisfies(error -> assertThat(((ProviderFailure) error).code()).isEqualTo(code))
                    .hasMessageNotContaining("test-key-one");
        }
    }

    @Test
    void distinguishesAnInvalidKeyReportedAsBadRequest() {
        status.set(400);
        errorMessage.set("API key not valid");
        try (var generation = provider().open(input(), "test-key-one")) {
            assertThatThrownBy(() -> generation.stream(ignored -> {}))
                    .isInstanceOf(ProviderFailure.class)
                    .satisfies(error -> assertThat(((ProviderFailure) error).code())
                            .isEqualTo(ProviderFailure.Code.INVALID_KEY));
        }
    }

    @Test
    void allowsAProviderPauseLongerThanTheHttpClientDefault() {
        waitForFirstChunk.set(true);
        secondChunkDelayMillis.set(11_000);
        List<String> chunks = new ArrayList<>();
        try (var generation = provider().open(input(), "test-key-one")) {
            generation.stream(chunk -> {
                chunks.add(chunk);
                firstChunk.countDown();
            });
        }
        assertThat(chunks).containsExactly("Hello", " world");
    }

    @Test
    void rejectsAnEmptyResponse() {
        response.set("");
        try (var generation = provider().open(input(), "test-key-one")) {
            assertThatThrownBy(() -> generation.stream(ignored -> {}))
                    .isInstanceOf(ProviderFailure.class)
                    .satisfies(error -> assertThat(((ProviderFailure) error).code())
                            .isEqualTo(ProviderFailure.Code.EMPTY_RESPONSE));
        }
    }

    @Test
    void rejectsMissingCredentials() {
        assertThatThrownBy(() -> provider().open(input(), " "))
                .isInstanceOf(ProviderFailure.class)
                .satisfies(error -> assertThat(((ProviderFailure) error).code())
                        .isEqualTo(ProviderFailure.Code.INVALID_KEY));
    }

    @Test
    void reportsAnActualNetworkTimeout() {
        responseDelayMillis.set(5_000);
        var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        try (var generation = new GeminiProvider(endpoint, 500).open(input(), "test-key-one")) {
            assertThatThrownBy(() -> generation.stream(ignored -> {}))
                    .isInstanceOf(ProviderFailure.class)
                    .satisfies(error -> assertThat(((ProviderFailure) error).code())
                            .isEqualTo(ProviderFailure.Code.TIMEOUT));
        }
    }

    @Test
    void rejectsMalformedProviderDataWithoutEchoingIt() {
        response.set("data: {not-json-secret}\n\n");
        try (var generation = provider().open(input(), "test-key-one")) {
            assertThatThrownBy(() -> generation.stream(ignored -> {}))
                    .isInstanceOf(ProviderFailure.class)
                    .hasMessageNotContaining("not-json-secret");
        }
    }

    @Test
    void cancellationStopsStreamingAfterTheFirstChunk() {
        waitForFirstChunk.set(true);
        List<String> chunks = new ArrayList<>();
        try (var generation = provider().open(input(), "test-key-one")) {
            assertThatThrownBy(() -> generation.stream(chunk -> {
                chunks.add(chunk);
                firstChunk.countDown();
                generation.close();
            })).isInstanceOf(ProviderFailure.class)
                    .satisfies(error -> assertThat(((ProviderFailure) error).code())
                            .isEqualTo(ProviderFailure.Code.CANCELLED));
        }
        assertThat(chunks).containsExactly("Hello");
    }

    @Test
    void cancellingAnIdleStreamUnblocksTheProvider() throws Exception {
        waitForFirstChunk.set(true);
        CountDownLatch release = new CountDownLatch(1);
        releaseResponse.set(release);
        var generation = provider().open(input(), "test-key-one");
        var worker = CompletableFuture.runAsync(() -> generation.stream(chunk -> firstChunk.countDown()));
        try {
            boolean arrived = firstChunk.await(60, TimeUnit.SECONDS);
            if (!arrived && worker.isDone()) {
                worker.join();
            }
            assertThat(arrived).isTrue();
            Thread.sleep(300);
            CompletableFuture.runAsync(generation::close).get(3, TimeUnit.SECONDS);
            assertThatThrownBy(() -> worker.get(3, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ProviderFailure.class);
        } finally {
            release.countDown();
        }
    }

    @Test
    void concurrentRequestsKeepTheirOwnCredentialsAndContext() {
        concurrentRequests.set(new CountDownLatch(2));
        GeminiProvider provider = provider();
        var first = CompletableFuture.runAsync(() -> stream(provider, "first-key", "first-message"));
        var second = CompletableFuture.runAsync(() -> stream(provider, "second-key", "second-message"));
        CompletableFuture.allOf(first, second).join();

        assertThat(bodiesByKey).hasSize(2);
        assertThat(bodiesByKey.get("first-key")).contains("first-message").doesNotContain("second-message");
        assertThat(bodiesByKey.get("second-key")).contains("second-message").doesNotContain("first-message");
    }

    @Test
    void timingLogsExcludeCredentialsAndConversationText() {
        Logger logger = (Logger) LoggerFactory.getLogger(GeminiProvider.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            stream(provider(), "private-test-key", "private-user-message");
            assertThat(captured.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("gemini_timing", "first_text_ms="));
            assertThat(captured.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
                    .doesNotContain("private-test-key", "private-user-message"));
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }

    private void stream(GeminiProvider provider, String key, String message) {
        var input = new ChatProvider.ChatInput("gemini-3.5-flash-lite", "Be helpful.",
                List.of(new ChatProvider.Message(ChatProvider.Role.USER, message)));
        try (var generation = provider.open(input, key)) {
            generation.stream(ignored -> {});
        }
    }

    private GeminiProvider provider() {
        return new GeminiProvider(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    private ChatProvider.ChatInput input() {
        return new ChatProvider.ChatInput("gemini-3.5-flash-lite", "Be helpful.",
                List.of(new ChatProvider.Message(ChatProvider.Role.USER, "Hello")));
    }
}
