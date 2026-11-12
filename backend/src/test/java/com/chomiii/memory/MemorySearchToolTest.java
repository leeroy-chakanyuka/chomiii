package com.chomiii.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chomiii.tools.ToolTurn;
import com.chomiii.tools.ToolExecutionContext;
import com.chomiii.tools.ToolRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemorySearchToolTest {
    @Test
    void returnsOwnerScopedFactsAndRejectsRepeatedSearches() {
        var memories = mock(MemoryService.class);
        var fact = new PersonalMemory(UUID.randomUUID(), "I like tacos", "user_explicit", Instant.now(), Instant.now());
        when(memories.search("owner-a", "dinner", null, 5)).thenReturn(List.of(fact));
        var delivered = new ArrayList<UUID>();
        var context = new ToolExecutionContext("owner-a", UUID.randomUUID(), UUID.randomUUID(), null, true,
                observation -> delivered.addAll(observation.references()));
        var turn = new ToolTurn(new ToolRegistry(List.of(new MemorySearchTool(memories, null))), context, false);

        assertThat(turn.invoke("memory.search", Map.of("query", "dinner")).toString())
                .contains("I like tacos", fact.id().toString(), "untrusted");
        assertThat(turn.invoke("memory.search", Map.of("query", "  DINNER  ", "limit", 5.0)))
                .containsEntry("error", "Repeated tool call.");
        assertThat(turn.invoke("memory.search", Map.of("query", "other")))
                .containsEntry("error", "Tool call limit reached.");
        assertThat(delivered).containsExactly(fact.id());
        verify(memories).search("owner-a", "dinner", null, 5);
        verify(memories, never()).search(org.mockito.ArgumentMatchers.eq("owner-b"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void modelCannotSupplyAnOwnerOrExceedTheResultLimit() {
        var memories = mock(MemoryService.class);
        var turn = new ToolTurn(new ToolRegistry(List.of(new MemorySearchTool(memories, null))),
                new ToolExecutionContext("owner-a", UUID.randomUUID(), UUID.randomUUID(), null, true, ignored -> {}), false);

        assertThat(turn.invoke("memory.search", Map.of("query", "dinner", "owner", "owner-b")))
                .containsKey("error");
        assertThat(turn.invoke("memory.search", Map.of("query", "dinner", "limit", 100)))
                .containsKey("error");
        verify(memories, never()).search(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void unavailableMemoryStoreReturnsAnErrorWithoutExposingItsMessage() {
        var memories = mock(MemoryService.class);
        when(memories.search("owner-a", "dinner", null, 5))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private database detail"));
        var turn = new ToolTurn(new ToolRegistry(List.of(new MemorySearchTool(memories, null))),
                new ToolExecutionContext("owner-a", UUID.randomUUID(), UUID.randomUUID(), null, true, ignored -> {}), false);

        assertThat(turn.invoke("memory.search", Map.of("query", "dinner")).toString())
                .contains("error").doesNotContain("private database detail");
    }

    @Test
    void sharedRegistryKeepsOwnersSeparateAndHidesMemoryWhenRecallIsOff() {
        var memories = mock(MemoryService.class);
        var a = new PersonalMemory(UUID.randomUUID(), "I like tacos", "user_explicit", Instant.now(), Instant.now());
        var b = new PersonalMemory(UUID.randomUUID(), "I like noodles", "user_explicit", Instant.now(), Instant.now());
        when(memories.search("owner-a", "dinner", null, 5)).thenReturn(List.of(a));
        when(memories.search("owner-b", "dinner", null, 5)).thenReturn(List.of(b));
        var registry = new ToolRegistry(List.of(new MemorySearchTool(memories, null)));
        var first = new ToolTurn(registry,
                new ToolExecutionContext("owner-a", UUID.randomUUID(), UUID.randomUUID(), null, true, ignored -> {}), false);
        var second = new ToolTurn(registry,
                new ToolExecutionContext("owner-b", UUID.randomUUID(), UUID.randomUUID(), null, true, ignored -> {}), false);
        var disabled = new ToolTurn(registry,
                new ToolExecutionContext("owner-a", UUID.randomUUID(), UUID.randomUUID(), null, false, ignored -> {}), false);

        assertThat(first.invoke("memory.search", Map.of("query", "dinner")).toString())
                .contains("tacos").doesNotContain("noodles");
        assertThat(second.invoke("memory.search", Map.of("query", "dinner")).toString())
                .contains("noodles").doesNotContain("tacos");
        assertThat(disabled.definitions()).isEmpty();
        assertThat(disabled.invoke("memory.search", Map.of("query", "dinner"))).containsKey("error");
    }
}
