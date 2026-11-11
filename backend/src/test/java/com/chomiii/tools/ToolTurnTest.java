package com.chomiii.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.chomiii.provider.ChatProvider;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolTurnTest {
    @Test
    void aSlowToolCannotHoldTheTurnIndefinitely() {
        InternalTool slow = new InternalTool() {
            @Override public ChatProvider.ToolSpec definition() {
                return new ChatProvider.ToolSpec("slow", "Slow test tool", Map.of("type", "object"));
            }
            @Override public Result execute(Map<String, Object> arguments) {
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return new Result(Map.of(), () -> {});
            }
        };
        var turn = new ToolTurn(List.of(slow), false);
        assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> assertThat(turn.invoke("slow", Map.of())).containsKey("error"));
    }
}
