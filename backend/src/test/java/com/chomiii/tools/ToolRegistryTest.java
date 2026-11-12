package com.chomiii.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {
    private static InternalTool tool(String name, boolean readOnly, AtomicInteger calls) {
        return new InternalTool() {
            @Override public ToolDescriptor definition() {
                return new ToolDescriptor(name, "A reviewed test capability",
                        Map.of("type", "object"), readOnly);
            }
            @Override public Result execute(Map<String, Object> arguments) {
                calls.incrementAndGet();
                return new Result(Map.of("ok", true), () -> {});
            }
        };
    }

    @Test
    void providerReceivesOnlyReadOnlyCapabilitiesBeforeApprovalGatewayExists() {
        var writes = new AtomicInteger();
        var reads = new AtomicInteger();
        var turn = new ToolTurn(List.of(
                tool("memory.search", true, reads),
                tool("reminder.create", false, writes)), false);

        assertThat(turn.definitions()).extracting(item -> item.name())
                .containsExactly("memory.search");
        assertThat(turn.invoke("reminder.create", Map.of()))
                .containsEntry("error", "Invalid tool call.");
        assertThat(writes.get()).isZero();
        assertThat(turn.invoke("memory.search", Map.of())).containsKey("output");
        assertThat(reads.get()).isEqualTo(1);
    }

    @Test
    void duplicateOrInvalidCapabilitiesCannotBeRegistered() {
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> new ToolRegistry(List.of(
                tool("memory.search", true, calls), tool("memory.search", true, calls))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolDescriptor(" ", "bad",
                Map.of("type", "object"), true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
