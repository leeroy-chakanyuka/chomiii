package com.chomiii.provider.gemini;

import com.chomiii.provider.ChatProvider;
import com.chomiii.provider.ProviderFailure;
import com.google.genai.Client;
import com.google.genai.ResponseStream;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.google.genai.types.Part;
import java.io.InterruptedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Component;

@Component
public class GeminiProvider implements ChatProvider {
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
        return new GeminiGeneration(input, apiKey);
    }

    private final class GeminiGeneration implements Generation {
        private final ChatInput input;
        private final String apiKey;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<Call> active = new AtomicReference<>();

        private GeminiGeneration(ChatInput input, String apiKey) {
            this.input = input;
            this.apiKey = apiKey;
        }

        @Override
        public void stream(Consumer<String> delta) {
            if (cancelled.get()) {
                throw failure(ProviderFailure.Code.CANCELLED);
            }

            HttpOptions options = HttpOptions.builder().baseUrl(endpoint.toString()).timeout(timeoutMillis)
                    .retryOptions(HttpRetryOptions.builder().attempts(1).build()).build();
            List<Content> messages = input.messages().stream()
                    .map(message -> Content.builder()
                            .role(message.role() == Role.USER ? "user" : "model")
                            .parts(Part.fromText(message.text())).build())
                    .toList();
            GenerateContentConfig config = GenerateContentConfig.builder()
                    .systemInstruction(Content.fromParts(Part.fromText(input.systemInstruction())))
                    .build();
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
                    .clientOptions(ClientOptions.builder().customHttpClient(transport).build()).build();
                    ResponseStream<GenerateContentResponse> response =
                            client.models.generateContentStream(input.model(), messages, config)) {
                if (cancelled.get()) {
                    throw failure(ProviderFailure.Code.CANCELLED);
                }
                boolean receivedText = false;
                for (GenerateContentResponse chunk : response) {
                    if (cancelled.get()) {
                        throw failure(ProviderFailure.Code.CANCELLED);
                    }
                    String text = chunk.text();
                    if (text != null && !text.isEmpty()) {
                        receivedText = true;
                        delta.accept(text);
                    }
                }
                if (!receivedText) {
                    throw failure(ProviderFailure.Code.EMPTY_RESPONSE);
                }
            } catch (ApiException exception) {
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
                if (cancelled.get()) {
                    throw failure(ProviderFailure.Code.CANCELLED);
                }
                throw failure(isTimeout(exception) ? ProviderFailure.Code.TIMEOUT : ProviderFailure.Code.UNAVAILABLE);
            }
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
