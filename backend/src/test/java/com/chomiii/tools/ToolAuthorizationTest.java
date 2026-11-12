package com.chomiii.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ToolAuthorizationTest {
    @Test
    void unapprovedWritesAreNeitherAdvertisedNorExecuted() {
        var called = new AtomicBoolean();
        var write = tool("calendar.update", "calendar.write", ToolDefinition.Effect.WRITE, called);
        var context = context("owner-a", UUID.randomUUID(), UUID.randomUUID());
        var turn = new ToolTurn(new ToolRegistry(List.of(write)), context, false);

        assertThat(turn.definitions()).isEmpty();
        assertThat(turn.invoke("calendar.update", Map.of())).containsEntry("errorCode", "NOT_AUTHORIZED");
        assertThat(called).isFalse();
    }

    @Test
    void deniedWritesAreNotProbedForAvailability() {
        var probed = new AtomicBoolean();
        InternalTool write = new InternalTool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition("calendar.update", "Test write", Map.of("type", "object"),
                        "calendar.write", ToolDefinition.Effect.WRITE);
            }
            @Override public boolean available(ToolExecutionContext context) { probed.set(true); return true; }
            @Override public Result execute(ToolExecutionContext context, Map<String, Object> arguments) {
                throw new AssertionError("Unapproved write executed");
            }
        };
        var turn = new ToolTurn(new ToolRegistry(List.of(write)), context("owner-a", UUID.randomUUID(), UUID.randomUUID()), false);

        assertThat(turn.definitions()).isEmpty();
        assertThat(turn.invoke("calendar.update", Map.of())).containsEntry("errorCode", "NOT_AUTHORIZED");
        assertThat(probed).isFalse();
    }

    @Test
    void authorizationReceivesTheTrustedOwnerTurnCapabilityAndArguments() {
        var called = new AtomicBoolean();
        var read = tool("example.read", "example.read", ToolDefinition.Effect.READ, called);
        UUID conversation = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        var observed = new AtomicReference<ToolExecutionContext>();
        ToolAuthorization deny = new ToolAuthorization() {
            @Override public boolean mayExpose(ToolExecutionContext context, ToolDefinition definition) { return true; }
            @Override public Decision authorize(ToolExecutionContext context, ToolDefinition definition, Map<String, Object> arguments) {
                observed.set(context);
                assertThat(definition.capability()).isEqualTo("example.read");
                assertThat(arguments).isEmpty();
                return Decision.DENY;
            }
        };
        var turn = new ToolTurn(new ToolRegistry(List.of(read), deny), context("owner-a", conversation, request), false);

        assertThat(turn.definitions()).extracting(ToolDefinition::name).containsExactly("example.read");
        assertThat(turn.invoke("example.read", Map.of())).containsEntry("errorCode", "NOT_AUTHORIZED");
        assertThat(observed.get().owner()).isEqualTo("owner-a");
        assertThat(observed.get().conversationId()).isEqualTo(conversation);
        assertThat(observed.get().requestId()).isEqualTo(request);
        assertThat(called).isFalse();
    }

    @Test
    void argumentsCannotChangeAfterAuthorization() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        InternalTool read = new InternalTool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition("example.read", "Test tool", Map.of("type", "object", "properties",
                        Map.of("query", Map.of("type", "string")), "required", List.of("query"),
                        "additionalProperties", false), "example.read", ToolDefinition.Effect.READ);
            }
            @Override public Result execute(ToolExecutionContext context, Map<String, Object> arguments) {
                entered.countDown();
                try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return new Result(Map.of("query", arguments.get("query")), () -> {});
            }
        };
        var turn = new ToolTurn(new ToolRegistry(List.of(read)), context("owner-a", UUID.randomUUID(), UUID.randomUUID()), false);
        var arguments = new java.util.HashMap<String, Object>();
        arguments.put("query", "original");
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> turn.invoke("example.read", arguments));
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            arguments.put("query", "changed");
            release.countDown();
            assertThat(result.get(5, java.util.concurrent.TimeUnit.SECONDS).toString()).contains("original").doesNotContain("changed");
        } finally {
            release.countDown();
        }
    }

    @Test
    void policyFailureDeniesExecution() {
        var called = new AtomicBoolean();
        var read = tool("example.read", "example.read", ToolDefinition.Effect.READ, called);
        ToolAuthorization broken = new ToolAuthorization() {
            @Override public boolean mayExpose(ToolExecutionContext context, ToolDefinition definition) { return true; }
            @Override public Decision authorize(ToolExecutionContext context, ToolDefinition definition, Map<String, Object> arguments) {
                throw new IllegalStateException("private policy detail");
            }
        };
        var turn = new ToolTurn(new ToolRegistry(List.of(read), broken),
                context("owner-a", UUID.randomUUID(), UUID.randomUUID()), false);

        assertThat(turn.invoke("example.read", Map.of()).toString())
                .contains("NOT_AUTHORIZED").doesNotContain("private policy detail");
        assertThat(called).isFalse();
    }

    private static ToolExecutionContext context(String owner, UUID conversation, UUID request) {
        return new ToolExecutionContext(owner, conversation, request, null, true, ignored -> {});
    }

    private static InternalTool tool(String name, String capability, ToolDefinition.Effect effect, AtomicBoolean called) {
        return new InternalTool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition(name, "Test tool", Map.of("type", "object"), capability, effect);
            }
            @Override public Result execute(ToolExecutionContext context, Map<String, Object> arguments) {
                called.set(true);
                return new Result(Map.of(), () -> {});
            }
        };
    }
}
