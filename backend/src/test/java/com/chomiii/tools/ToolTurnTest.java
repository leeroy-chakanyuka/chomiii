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
                        Map.of("type", "object"), "calendar.write", ToolDefinition.Effect.WRITE);
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
                return new ToolDefinition("slow", "Slow test tool", Map.of("type", "object"), "test.read", ToolDefinition.Effect.READ);
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
                return new ToolDefinition("large", "Large test result", Map.of("type", "object"), "test.read", ToolDefinition.Effect.READ);
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
                return new ToolDefinition("slow", "Slow test tool", Map.of("type", "object"), "test.read", ToolDefinition.Effect.READ);
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

    @Test
    void aSlowTraceStartDoesNotHoldTheTurnAfterItsDeadline() throws Exception {
        var traces = org.mockito.Mockito.mock(com.chomiii.tools.trace.ToolTraceService.class);
        var requested = org.mockito.Mockito.mock(com.chomiii.tools.trace.ToolTraceDto.Trace.class);
        var id = java.util.UUID.randomUUID();
        org.mockito.Mockito.when(requested.invocationId()).thenReturn(id);
        org.mockito.Mockito.when(traces.recordRequested(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(requested);
        org.mockito.Mockito.when(traces.recordFinished(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(id),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(requested);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.when(traces.recordStarted("owner", id)).thenAnswer(ignored -> {
            entered.countDown();
            boolean done = false;
            while (!done) {
                try { done = release.await(10, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { /* Simulate a blocked database call. */ }
            }
            return java.time.Instant.now();
        });
        var executed = new java.util.concurrent.atomic.AtomicBoolean();
        InternalTool read = new InternalTool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition("example.read", "Read", Map.of("type", "object"), "memory.read", ToolDefinition.Effect.READ);
            }
            @Override public Result execute(ToolExecutionContext context, Map<String, Object> arguments) {
                executed.set(true);
                return new Result(Map.of(), () -> {});
            }
        };
        var context = new ToolExecutionContext("owner", java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), null, true, ignored -> {});
        var turn = new ToolTurn(new ToolRegistry(List.of(read)), context, false, traces, "gemini", "model", ignored -> {});
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(6),
                    () -> assertThat(turn.invoke("example.read", Map.of())).containsEntry("errorCode", "TIMEOUT"));
            assertThat(entered.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(executed).isFalse();
        } finally {
            release.countDown();
        }
    }

    private static ToolTurn turn(InternalTool tool) {
        ToolAuthorization policy = new ToolAuthorization() {
            @Override public boolean mayExpose(ToolExecutionContext context, ToolDefinition definition) {
                return "test.read".equals(definition.capability()) && definition.effect() == ToolDefinition.Effect.READ;
            }
            @Override public Decision authorize(ToolExecutionContext context, ToolDefinition definition, Map<String, Object> arguments) {
                return mayExpose(context, definition) ? Decision.ALLOW : Decision.DENY;
            }
        };
        return new ToolTurn(new ToolRegistry(List.of(tool), policy),
                new ToolExecutionContext("owner", java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), null, true, ignored -> {}), false);
    }
}
