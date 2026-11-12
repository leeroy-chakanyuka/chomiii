package com.chomiii.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolTurnTest {
    @Test
    void writeToolsCannotBeExposedOrExecutedBeforeTheApprovalGatewayExists() {
        InternalTool dangerous = new InternalTool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition("calendar.update", "Change a calendar event",
                        Map.of("type", "object"), ToolDefinition.Effect.WRITE);
            }
            @Override public Result execute(Map<String, Object> arguments) {
                throw new AssertionError("A write tool must never execute without approval");
            }
        };
        var turn = new ToolTurn(List.of(dangerous), false);
        assertThat(turn.definitions()).isEmpty();
        assertThat(turn.invoke("calendar.update", Map.of())).containsKey("error");
    }

    @Test
    void aSlowToolCannotHoldTheTurnIndefinitely() {
        InternalTool slow = new InternalTool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition("slow", "Slow test tool", Map.of("type", "object"), ToolDefinition.Effect.READ);
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

    @Test
    void resultBudgetUsesEncodedBytes() {
        InternalTool large = new InternalTool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition("large", "Large test result", Map.of("type", "object"), ToolDefinition.Effect.READ);
            }
            @Override public Result execute(Map<String, Object> arguments) {
                return new Result(Map.of("text", "🦄".repeat(1_200)), () -> {});
            }
        };
        var turn = new ToolTurn(List.of(large), false);
        assertThat(turn.invoke("large", Map.of())).containsEntry("error", "Tool result exceeds the context budget.");
    }
}
