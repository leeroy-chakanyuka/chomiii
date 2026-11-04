package com.chomiii.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ConversationDto {
    private ConversationDto() {}

    public record Summary(UUID id, String title, Instant createdAt, Instant updatedAt) {}

    public record Message(UUID id, String role, String content, String status, Instant createdAt) {}

    public record Detail(UUID id, String title, Instant createdAt, Instant updatedAt, List<Message> messages) {}
}
