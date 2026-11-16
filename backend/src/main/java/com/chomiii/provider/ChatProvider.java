package com.chomiii.provider;

import com.chomiii.tools.ToolDefinition;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public interface ChatProvider {
    String id();

    Generation open(ChatInput input, String apiKey);

    default Generation open(ChatInput input, String apiKey, Tools tools) {
        return open(input, apiKey);
    }

    interface Tools extends AutoCloseable {
        List<ToolDefinition> definitions();
        Map<String, Object> invoke(String name, Map<String, Object> arguments);
        default boolean bufferTextUntilFinal() { return false; }
        default void beforeContinuation(long inputBytes) { }
        @Override default void close() { }
    }

    enum Role { USER, ASSISTANT }

    record Message(Role role, String text) {}

    record ChatInput(String model, String systemInstruction, List<Message> messages) {
        public ChatInput {
            messages = List.copyOf(messages);
        }
    }

    record TokenUsage(Long inputTokens, Long outputTokens, Long thoughtTokens, Long totalTokens, int requests) {}

    interface Generation extends AutoCloseable {
        void stream(Consumer<String> delta);

        default Optional<TokenUsage> usage() { return Optional.empty(); }

        @Override
        void close();
    }
}
