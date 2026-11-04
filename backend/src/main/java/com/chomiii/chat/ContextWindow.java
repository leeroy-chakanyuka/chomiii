package com.chomiii.chat;

import com.chomiii.provider.ChatProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ContextWindow {
    private ContextWindow() {}

    public record Turn(String role, String text, String status) {}

    public static List<ChatProvider.Message> select(List<Turn> history, String current, int maxMessages, int maxChars) {
        if (current.length() > maxChars || maxMessages < 1) {
            throw new IllegalArgumentException("Message exceeds the context limit");
        }
        List<ChatProvider.Message> selected = new ArrayList<>();
        selected.add(new ChatProvider.Message(ChatProvider.Role.USER, current));
        int chars = current.length();
        for (int i = history.size() - 1; i >= 0 && selected.size() < maxMessages; i--) {
            Turn turn = history.get(i);
            if (!"complete".equals(turn.status())) {
                continue;
            }
            if (chars + turn.text().length() > maxChars) {
                break;
            }
            selected.add(new ChatProvider.Message("user".equals(turn.role())
                    ? ChatProvider.Role.USER : ChatProvider.Role.ASSISTANT, turn.text()));
            chars += turn.text().length();
        }
        Collections.reverse(selected);
        return List.copyOf(selected);
    }
}
