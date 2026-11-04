package com.chomiii.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Versioned, trusted identity used by the chat provider. Runtime context and
 * user messages remain separate from this system instruction.
 */
public final class AgentInstructions {
    private static final String RESOURCE = "agent/SYSTEM.md";
    private static final int MAX_PROMPT_CHARS = 2_000;
    private final String systemPrompt;

    public AgentInstructions() {
        this(AgentInstructions.class.getClassLoader());
    }

    AgentInstructions(ClassLoader loader) {
        try (InputStream stream = loader.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing agent resource: " + RESOURCE);
            }
            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip();
            if (content.isEmpty() || content.length() > MAX_PROMPT_CHARS) {
                throw new IllegalStateException("Invalid agent identity size");
            }
            systemPrompt = content;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load agent identity", exception);
        }
    }

    public String systemPrompt() {
        return systemPrompt;
    }
}
