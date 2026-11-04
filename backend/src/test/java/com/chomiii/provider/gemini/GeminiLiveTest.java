package com.chomiii.provider.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import com.chomiii.provider.ChatProvider;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "CHOMIII_RUN_GEMINI_SMOKE", matches = "true")
class GeminiLiveTest {
    @Test
    void receivesRealGeminiText() {
        String key = System.getenv("GEMINI_API_KEY");
        assertThat(key).as("GEMINI_API_KEY must contain the authorized test key").isNotBlank();
        var input = new ChatProvider.ChatInput("gemini-3.5-flash-lite", "You are a helpful assistant.",
                List.of(new ChatProvider.Message(ChatProvider.Role.USER, "Reply with the single word hello.")));
        List<String> chunks = new ArrayList<>();

        try (var generation = new GeminiProvider().open(input, key)) {
            generation.stream(chunks::add);
        }

        String reply = String.join("", chunks);
        assertThat(reply).isNotBlank();
        System.out.println("Gemini response: " + reply.replace(key, "[redacted]").replace('\n', ' '));
    }
}
