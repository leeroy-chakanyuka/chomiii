package com.chomiii.provider;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface ChatProvider {
    String id();

    Generation open(ChatInput input, String apiKey);

    default Generation open(ChatInput input, String apiKey, Tools tools) {
        return open(input, apiKey);
    }

    record ToolSpec(String name, String description, Map<String, Object> parameters) {
        public ToolSpec {
            parameters = Map.copyOf(parameters);
        }
    }

    interface Tools {
        List<ToolSpec> definitions();
        Map<String, Object> invoke(String name, Map<String, Object> arguments);
        default boolean bufferTextUntilFinal() { return false; }
    }

    enum Role { USER, ASSISTANT }

    record Message(Role role, String text) {}

    record ChatInput(String model, String systemInstruction, List<Message> messages) {
        public ChatInput {
            messages = List.copyOf(messages);
        }
    }

    interface Generation extends AutoCloseable {
        void stream(Consumer<String> delta);

        @Override
        void close();
    }
}
