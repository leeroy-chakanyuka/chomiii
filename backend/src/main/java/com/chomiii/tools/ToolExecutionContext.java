package com.chomiii.tools;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Trusted per-turn data supplied by the application, never by a model. */
public final class ToolExecutionContext {
    public record Observation(String toolName, List<UUID> references) {
        public Observation { references = List.copyOf(references); }
    }

    private final String owner;
    private final String apiKey;
    private final boolean memoryRecallEnabled;
    private final Consumer<Observation> observer;

    public ToolExecutionContext(String owner, String apiKey, boolean memoryRecallEnabled, Consumer<Observation> observer) {
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("Owner is required");
        this.owner = owner;
        this.apiKey = apiKey;
        this.memoryRecallEnabled = memoryRecallEnabled;
        this.observer = Objects.requireNonNull(observer);
    }

    public String owner() { return owner; }
    public String apiKey() { return apiKey; }
    public boolean memoryRecallEnabled() { return memoryRecallEnabled; }
    public void delivered(Observation observation) { observer.accept(observation); }
}
