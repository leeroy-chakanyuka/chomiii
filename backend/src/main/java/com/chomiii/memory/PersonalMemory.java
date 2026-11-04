package com.chomiii.memory;

import java.time.Instant;
import java.util.UUID;

/** A fact the user explicitly chose to save; never automatically inferred. */
public record PersonalMemory(UUID id, String content, String source, Instant createdAt, Instant updatedAt) {}
