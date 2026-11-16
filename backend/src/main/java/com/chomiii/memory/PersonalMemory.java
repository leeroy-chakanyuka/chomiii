package com.chomiii.memory;

import java.time.Instant;
import java.util.UUID;

/**
 * A user-controlled memory. Inferences remain suggestions until approved.
 * {@code claimTopic} is optional extraction provenance: a general subject
 * label for the fact used only by the conservative conflict gate; user-edited
 * content clears it.
 */
public record PersonalMemory(UUID id, String content, String source,
        Instant createdAt, Instant updatedAt, String status, double confidence,
        UUID sourceConversationId, String claimTopic, UUID supersededBy) {
    public PersonalMemory(UUID id, String content, String source, Instant createdAt, Instant updatedAt) {
        this(id, content, source, createdAt, updatedAt, "active", 1.0, null, null, null);
    }

    public PersonalMemory(UUID id, String content, String source, Instant createdAt, Instant updatedAt,
            String status, double confidence, UUID sourceConversationId) {
        this(id, content, source, createdAt, updatedAt, status, confidence, sourceConversationId, null, null);
    }

    public PersonalMemory(UUID id, String content, String source, Instant createdAt, Instant updatedAt,
            String status, double confidence, UUID sourceConversationId, String claimTopic) {
        this(id, content, source, createdAt, updatedAt, status, confidence, sourceConversationId, claimTopic, null);
    }
}
