package com.chomiii.todo;

import java.time.Instant;
import java.util.UUID;

public record PersonalTodo(UUID id, String title, String description, String status, Instant dueAt,
        String timezone, UUID sourceConversationId, UUID sourcePlanId, Instant createdAt, Instant updatedAt) {
}
