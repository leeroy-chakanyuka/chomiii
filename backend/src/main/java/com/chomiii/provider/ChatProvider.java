package com.chomiii.provider;

import java.util.List;
import java.util.function.Consumer;

public interface ChatProvider {
    String id();

    Generation open(ChatInput input, String apiKey);

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
