package com.chomiii.memory;

import java.util.List;
import java.util.UUID;

/**
 * One derived, category-bounded profile paragraph with the canonical memory
 * IDs it was composed from. The paragraph is a re-composition of approved
 * memory text, never an independent editable source of truth.
 */
public record ProfileParagraph(String category, String text, List<UUID> sourceIds) {
    public ProfileParagraph {
        sourceIds = List.copyOf(sourceIds);
    }
}
