package com.chomiii.provider.gemini;

import com.chomiii.provider.ChatProvider;
import com.chomiii.provider.ProviderFailure;
import com.google.genai.Client;
import com.google.genai.ResponseStream;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.google.genai.types.Part;
import com.google.genai.types.Tool;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GeminiProvider implements ChatProvider {
    private static final Logger log = LoggerFactory.getLogger(GeminiProvider.class);
    private final URI endpoint;
    private final int timeoutMillis;

    public GeminiProvider() {
        this(URI.create("https://generativelanguage.googleapis.com"), 120_000);
    }

    GeminiProvider(URI endpoint) {
        this(endpoint, 120_000);
    }

    GeminiProvider(URI endpoint, int timeoutMillis) {
        this.endpoint = endpoint;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public String id() {
        return "gemini";
    }

    @Override
    public Generation open(ChatInput input, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ProviderFailure(ProviderFailure.Code.INVALID_KEY, "Enter a Gemini API key.");
        }
        return new GeminiGeneration(input, apiKey, null);
    }

    @Override
    public Generation open(ChatInput input, String apiKey, Tools tools) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ProviderFailure(ProviderFailure.Code.INVALID_KEY, "Enter a Gemini API key.");
        }
        return new GeminiGeneration(input, apiKey, tools);
    }

    private final class GeminiGeneration implements Generation {
        private final ChatInput input;
        private final String apiKey;
        private final Tools tools;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<Call> active = new AtomicReference<>();
        private volatile TokenUsage usage;

        private GeminiGeneration(ChatInput input, String apiKey, Tools tools) {
            this.input = input;
            this.apiKey = apiKey;
            this.tools = tools;
        }

        @Override
        public void stream(Consumer<String> delta) {
            long started = System.nanoTime();
            long clientReady = 0;
            long streamOpen = 0;
            long firstText = 0;
            String outcome = "complete";
            List<GenerateContentResponseUsageMetadata> usageByRound = new ArrayList<>();
            int completedRounds = 0;
            if (cancelled.get()) {
                throw failure(ProviderFailure.Code.CANCELLED);
            }

            HttpOptions options = HttpOptions.builder().baseUrl(endpoint.toString()).timeout(timeoutMillis)
                    .retryOptions(HttpRetryOptions.builder().attempts(1).build()).build();
            List<Content> messages = new ArrayList<>(input.messages().stream()
                    .map(message -> Content.builder()
                            .role(message.role() == Role.USER ? "user" : "model")
                            .parts(Part.fromText(message.text())).build())
                    .toList());
            OkHttpClient transport = new OkHttpClient.Builder()
                    .connectTimeout(Duration.ofMillis(Math.min(timeoutMillis, 15_000)))
                    .readTimeout(Duration.ofMillis(timeoutMillis))
                    .writeTimeout(Duration.ofMillis(timeoutMillis))
                    .addInterceptor(chain -> {
                        Call call = chain.call();
                        active.set(call);
                        if (cancelled.get()) {
                            call.cancel();
                        }
                        return chain.proceed(chain.request());
                    }).build();

            try (Client client = Client.builder().enterprise(false).apiKey(apiKey).httpOptions(options)
                    .clientOptions(ClientOptions.builder().customHttpClient(transport).build()).build()) {
                clientReady = System.nanoTime();
                int callsUsed = 0;
                for (int round = 0; round < 3; round++) {
                    boolean toolsAvailable = tools != null && callsUsed < 2 && !tools.definitions().isEmpty();
                    var config = GenerateContentConfig.builder()
                            .systemInstruction(Content.fromParts(Part.fromText(input.systemInstruction())));
                    if (tools != null) config.maxOutputTokens(4096);
                    if (toolsAvailable) {
                        List<FunctionDeclaration> declarations = tools.definitions().stream()
                                .map(spec -> FunctionDeclaration.builder().name(spec.name())
                                        .description(spec.description()).parametersJsonSchema(spec.parameters()).build())
                                .toList();
                        config.tools(Tool.builder().functionDeclarations(declarations).build());
                    }
                    List<Part> modelParts = new ArrayList<>();
                    List<FunctionCall> calls = new ArrayList<>();
                    StringBuilder buffered = new StringBuilder();
                    boolean receivedText = false;
                    int modelChars = 0;
                    GenerateContentResponseUsageMetadata roundUsage = null;
                    GenerateContentConfig requestConfig = config.build();
                    if (round > 0 && tools != null) {
                        long inputBytes = requestConfig.toJson().getBytes(StandardCharsets.UTF_8).length + 1_024L;
                        for (Content message : messages) inputBytes += message.toJson().getBytes(StandardCharsets.UTF_8).length;
                        tools.beforeContinuation(inputBytes);
                    }
                    try (ResponseStream<GenerateContentResponse> response =
                            client.models.generateContentStream(input.model(), messages, requestConfig)) {
                        if (streamOpen == 0) streamOpen = System.nanoTime();
                        for (GenerateContentResponse chunk : response) {
                            if (cancelled.get()) throw failure(ProviderFailure.Code.CANCELLED);
                            if (chunk.usageMetadata().isPresent()) roundUsage = chunk.usageMetadata().get();
                            chunk.checkFinishReason();
                            var candidates = chunk.candidates().orElse(List.of());
                            if (candidates.isEmpty()) continue;
                            var content = candidates.getFirst().content();
                            if (content.isEmpty()) continue;
                            for (Part part : content.get().parts().orElse(List.of())) {
                                if (toolsAvailable) {
                                    modelParts.add(part);
                                    modelChars += part.text().map(String::length).orElse(0);
                                    if (modelParts.size() > 256 || modelChars > 20_000) {
                                        throw failure(ProviderFailure.Code.INVALID_REQUEST);
                                    }
                                }
                                part.functionCall().ifPresent(calls::add);
                                if (part.thought().orElse(false)) continue;
                                String text = part.text().orElse("");
                                if (!text.isEmpty()) {
                                    if (firstText == 0) firstText = System.nanoTime();
                                    receivedText = true;
                                    if (tools != null && tools.bufferTextUntilFinal()) {
                                        if (buffered.length() + text.length() > 20_000) {
                                            throw failure(ProviderFailure.Code.INVALID_REQUEST);
                                        }
                                        buffered.append(text);
                                    } else {
                                        delta.accept(text);
                                    }
                                }
                            }
                        }
                    }
                    completedRounds++;
                    if (roundUsage != null) usageByRound.add(roundUsage);
                    if (calls.isEmpty()) {
                        if (!receivedText) throw failure(ProviderFailure.Code.EMPTY_RESPONSE);
                        if (!buffered.isEmpty()) delta.accept(buffered.toString());
                        break;
                    }
                    if (!toolsAvailable || calls.size() > 2 - callsUsed) {
                        throw failure(ProviderFailure.Code.INVALID_REQUEST);
                    }
                    callsUsed += calls.size();
                    messages.add(Content.builder().role("model").parts(modelParts).build());
                    List<Part> results = new ArrayList<>();
                    for (FunctionCall call : calls) {
                        if (cancelled.get()) throw failure(ProviderFailure.Code.CANCELLED);
                        String name = call.name().orElse("");
                        Map<String, Object> result = tools.invoke(name, call.args().orElse(Map.of()));
                        var functionResponse = FunctionResponse.builder().name(name).response(result);
                        call.id().ifPresent(functionResponse::id);
                        results.add(Part.builder().functionResponse(functionResponse.build()).build());
                    }
                    messages.add(Content.builder().role("user").parts(results).build());
                }
            } catch (ApiException exception) {
                outcome = "upstream_" + exception.code();
                throw failure(switch (exception.code()) {
                    case 400 -> invalidKeyMessage(exception.message())
                            ? ProviderFailure.Code.INVALID_KEY : ProviderFailure.Code.INVALID_REQUEST;
                    case 401, 403 -> ProviderFailure.Code.INVALID_KEY;
                    case 404 -> ProviderFailure.Code.UNSUPPORTED_MODEL;
                    case 408 -> ProviderFailure.Code.TIMEOUT;
                    case 429 -> ProviderFailure.Code.RATE_LIMITED;
                    default -> ProviderFailure.Code.UNAVAILABLE;
                });
            } catch (GenAiIOException exception) {
                outcome = cancelled.get() ? "cancelled" : "transport_error";
                if (cancelled.get()) {
                    throw failure(ProviderFailure.Code.CANCELLED);
                }
                throw failure(isTimeout(exception) ? ProviderFailure.Code.TIMEOUT : ProviderFailure.Code.UNAVAILABLE);
            } catch (RuntimeException exception) {
                outcome = cancelled.get() ? "cancelled" : "failed";
                throw exception;
            } finally {
                if (completedRounds > 0) usage = tokenUsage(usageByRound, completedRounds);
                log.info("gemini_timing model={} client_init_ms={} request_open_ms={} first_text_ms={} total_ms={} outcome={}",
                        input.model(), elapsed(started, clientReady), elapsed(clientReady, streamOpen),
                        elapsed(started, firstText),
                        elapsed(started, System.nanoTime()), outcome);
            }
        }

        @Override
        public Optional<TokenUsage> usage() {
            return Optional.ofNullable(usage);
        }

        @Override
        public void close() {
            cancelled.set(true);
            Call call = active.get();
            if (call != null) {
                call.cancel();
            }
        }
    }

    private static TokenUsage tokenUsage(List<GenerateContentResponseUsageMetadata> rounds, int count) {
        if (rounds.size() != count) return new TokenUsage(null, null, null, null, count);
        return new TokenUsage(sum(rounds, GenerateContentResponseUsageMetadata::promptTokenCount),
                sum(rounds, GenerateContentResponseUsageMetadata::candidatesTokenCount),
                sum(rounds, GenerateContentResponseUsageMetadata::thoughtsTokenCount),
                sum(rounds, GenerateContentResponseUsageMetadata::totalTokenCount), count);
    }

    private static Long sum(List<GenerateContentResponseUsageMetadata> rounds,
            java.util.function.Function<GenerateContentResponseUsageMetadata, Optional<Integer>> field) {
        long total = 0;
        for (var round : rounds) {
            var value = field.apply(round);
            if (value.isEmpty()) return null;
            total += value.get();
        }
        return total;
    }

    private static long elapsed(long start, long end) {
        return end == 0 ? -1 : (end - start) / 1_000_000;
    }

    private static boolean isTimeout(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedIOException) {
                return true;
            }
        }
        return false;
    }

    private static boolean invalidKeyMessage(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return normalized.contains("api key") || normalized.contains("api_key_invalid");
    }

    private static ProviderFailure failure(ProviderFailure.Code code) {
        return new ProviderFailure(code, switch (code) {
            case INVALID_KEY -> "Gemini rejected the API key.";
            case INVALID_REQUEST -> "Gemini rejected the request.";
            case UNSUPPORTED_MODEL -> "This Gemini model is not available for your key.";
            case RATE_LIMITED -> "Gemini's rate limit was reached. Try again later.";
            case TIMEOUT -> "Gemini took too long to respond.";
            case UNAVAILABLE -> "Gemini is unavailable. Try again later.";
            case EMPTY_RESPONSE -> "Gemini returned no text.";
            case CANCELLED -> "Generation stopped.";
        });
    }
}
