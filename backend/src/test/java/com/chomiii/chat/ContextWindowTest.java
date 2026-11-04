package com.chomiii.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.chomiii.provider.ChatProvider;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContextWindowTest {
    @Test
    void retainsOrderedCompleteTurnsWithoutFailedAssistantText() {
        var history = List.of(
                new ContextWindow.Turn("user", "My favourite language is Java", "complete"),
                new ContextWindow.Turn("assistant", "I will remember that in this chat.", "complete"),
                new ContextWindow.Turn("user", "Try again", "complete"),
                new ContextWindow.Turn("assistant", "Partial response", "failed"));

        assertThat(ContextWindow.select(history, "What language?", 10, 200)).containsExactly(
                new ChatProvider.Message(ChatProvider.Role.USER, "My favourite language is Java"),
                new ChatProvider.Message(ChatProvider.Role.ASSISTANT, "I will remember that in this chat."),
                new ChatProvider.Message(ChatProvider.Role.USER, "Try again"),
                new ChatProvider.Message(ChatProvider.Role.USER, "What language?"));
    }

    @Test
    void keepsNewestContextWithinMessageAndCharacterLimits() {
        var history = List.of(
                new ContextWindow.Turn("user", "old", "complete"),
                new ContextWindow.Turn("assistant", "older", "complete"),
                new ContextWindow.Turn("user", "recent", "complete"),
                new ContextWindow.Turn("assistant", "newest", "complete"));

        assertThat(ContextWindow.select(history, "today", 3, 18)).containsExactly(
                new ChatProvider.Message(ChatProvider.Role.USER, "recent"),
                new ChatProvider.Message(ChatProvider.Role.ASSISTANT, "newest"),
                new ChatProvider.Message(ChatProvider.Role.USER, "today"));
    }
}
