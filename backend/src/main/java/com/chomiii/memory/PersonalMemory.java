package com.chomiii.memory;

import java.time.Instant;
import java.util.UUID;

/** A user-controlled memory. Inferences remain suggestions until approved. */
public record PersonalMemory(UUID id, String content, String source,
        Instant createdAt, Instant updatedAt, String status, double confidence,
        UUID sourceConversationId) {
    public PersonalMemory(UUID id, String content, String source, Instant createdAt, Instant updatedAt) {
        this(id, content, source, createdAt, updatedAt, "active", 1.0, null);
    }
}
