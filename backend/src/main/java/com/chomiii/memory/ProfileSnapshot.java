package com.chomiii.memory;

import java.util.List;
import java.util.UUID;

/**
 * Owner-scoped derived profile snapshot: the public, human-readable profile
 * text plus each category paragraph with the canonical memory IDs it cites.
 * A snapshot is only valid while it matches the owner's current canonical
 * memory state; consumers must not treat stale snapshots as authoritative.
 */
public record ProfileSnapshot(String text, List<ProfileParagraph> paragraphs, List<UUID> sourceIds,
        List<ProfileParagraph> topics) {
    public ProfileSnapshot {
        paragraphs = List.copyOf(paragraphs);
        sourceIds = List.copyOf(sourceIds);
        topics = List.copyOf(topics);
    }

    public static ProfileSnapshot empty() {
        return new ProfileSnapshot("", List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return paragraphs.isEmpty() && sourceIds.isEmpty();
    }
}
