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
            @Override public Result execute(ToolExecutionContext context, Map<String, Object> arguments) {
                throw new AssertionError("A write tool must never execute without approval");
            }
        };
        var turn = turn(dangerous);
        assertThat(turn.definitions()).isEmpty();
        assertThat(turn.invoke("calendar.update", Map.of())).containsKey("error");
    }

    @Test
    void aSlowToolCannotHoldTheTurnIndefinitely() {
        InternalTool slow = new InternalTool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition("slow", "Slow test tool", Map.of("type", "object"), ToolDefinition.Effect.READ);
            }
            @Override public Result execute(ToolExecutionContext context, Map<String, Object> arguments) {
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return new Result(Map.of(), () -> {});
            }
        };
        var turn = turn(slow);
        assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> assertThat(turn.invoke("slow", Map.of())).containsKey("error"));
    }

    @Test
    void resultBudgetUsesEncodedBytes() {
        InternalTool large = new InternalTool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition("large", "Large test result", Map.of("type", "object"), ToolDefinition.Effect.READ);
            }
            @Override public Result execute(ToolExecutionContext context, Map<String, Object> arguments) {
                return new Result(Map.of("text", "🦄".repeat(1_200)), () -> {});
            }
        };
        var turn = turn(large);
        assertThat(turn.invoke("large", Map.of())).containsEntry("error", "Tool result exceeds the context budget.");
    }

    @Test
    void cancellingATurnInterruptsAnInFlightTool() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var interrupted = new java.util.concurrent.CountDownLatch(1);
        InternalTool slow = new InternalTool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition("slow", "Slow test tool", Map.of("type", "object"), ToolDefinition.Effect.READ);
            }
            @Override public Result execute(ToolExecutionContext context, Map<String, Object> arguments) {
                entered.countDown();
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException cancelled) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return new Result(Map.of(), () -> {});
            }
        };
        var turn = turn(slow);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> turn.invoke("slow", Map.of()));
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            turn.close();
            assertThat(interrupted.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(result.get(5, java.util.concurrent.TimeUnit.SECONDS)).containsEntry("errorCode", "CANCELLED");
        }
    }

    private static ToolTurn turn(InternalTool tool) {
        return new ToolTurn(new ToolRegistry(List.of(tool)),
                new ToolExecutionContext("owner", null, true, ignored -> {}), false);
    }
}
