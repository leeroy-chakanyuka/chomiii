package com.chomiii.todo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.chomiii.tools.ToolDefinition;
import com.chomiii.tools.ToolExecutionContext;
import com.chomiii.tools.ToolSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TodoToolTest {
    private final TodoService service = mock(TodoService.class);
    private final UUID conversation = UUID.randomUUID();
    private final UUID action = UUID.randomUUID();
    private final ToolExecutionContext context = new ToolExecutionContext("alice", conversation, UUID.randomUUID(),
            null, false, ignored -> {}, action);

    private PersonalTodo todo(UUID id, String title, String status) {
        return new PersonalTodo(id, title, null, status, null, null, conversation, null, Instant.EPOCH, Instant.EPOCH);
    }

    @Test void definitionsAreStrictAndCapabilitiesMatchEffects() {
        var tools = List.of(new TodoListTool(service), new TodoCreateTool(service), new TodoCompleteTool(service));
        assertThat(tools.stream().map(tool -> tool.definition().name()).toList())
                .containsExactly("todo.list", "todo.create", "todo.complete");
        for (var tool : tools) {
            var definition = tool.definition();
            ToolSchemaValidator.validateSchema(definition.parameters());
            assertThat(definition.parameters()).containsEntry("additionalProperties", false);
            assertThat(definition.capability()).isEqualTo(definition.effect() == ToolDefinition.Effect.READ ? "todo.read" : "todo.write");
            assertThatThrownBy(() -> tool.execute(context, Map.of("owner", "bob")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void createUsesTrustedActionAndConversationAndReturnsStoredStatus() {
        UUID id = UUID.randomUUID();
        var request = new TodoService.CreateTodo(" Pay bill ", " note ", Instant.parse("2030-01-01T00:00:00Z"),
                "Europe/London", conversation, null);
        when(service.create("alice", action, request)).thenReturn(todo(id, "Pay bill", "completed"));
        var tool = new TodoCreateTool(service);
        var arguments = Map.<String, Object>of("title", " Pay bill ", "description", " note ",
                "dueAt", "2030-01-01T00:00:00Z", "timezone", "Europe/London");
        assertThat(tool.execute(context, arguments).output()).containsEntry("id", id.toString()).containsEntry("status", "completed");
        tool.execute(context, arguments);
        verify(service, times(2)).create("alice", action, request);
        var noAction = new ToolExecutionContext("alice", conversation, UUID.randomUUID(), null, false, ignored -> {});
        assertThatThrownBy(() -> tool.execute(noAction, arguments)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tool.execute(context, Map.of("title", "x", "actionId", action.toString())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.execute(context, Map.of("title", "x", "dueAt", "tomorrow")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void plannedCreateUsesTrustedPlanAndReportsExistingOutcome() {
        UUID plan = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        var plannedContext = new ToolExecutionContext("alice", conversation, UUID.randomUUID(),
                null, false, ignored -> {}, action, plan);
        var request = new TodoService.CreateTodo("Milk", null, null, null, conversation, plan);
        when(service.createWithOutcome("alice", action, request))
                .thenReturn(new TodoService.CreateResult(todo(id, "Milk", "open"), "already_exists"));
        assertThat(new TodoCreateTool(service).execute(plannedContext, Map.of("title", "Milk")).output())
                .containsEntry("id", id.toString()).containsEntry("outcome", "already_exists");
        assertThatThrownBy(() -> new TodoCreateTool(service).execute(plannedContext,
                Map.of("title", "Milk", "planId", plan.toString()))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void listScopesOwnersFiltersAndFitsByteBudget() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.list("alice", TodoService.Status.OPEN, 21)).thenReturn(
                java.util.stream.IntStream.range(0, 20).mapToObj(i -> todo(id, "\u0001".repeat(200), "open")).toList());
        when(service.list("bob", null, 11)).thenReturn(List.of(todo(id, "Bob only", "open")));
        var tool = new TodoListTool(service);
        var result = tool.execute(context, Map.of("status", "open", "limit", 20)).output();
        assertThat(result).containsEntry("truncated", true);
        assertThat(new ObjectMapper().writeValueAsBytes(result).length).isLessThanOrEqualTo(3000);
        var bob = new ToolExecutionContext("bob", conversation, UUID.randomUUID(), null, false, ignored -> {});
        assertThat(tool.execute(bob, Map.of()).output().toString()).contains("Bob only");
        assertThat(result.toString()).doesNotContain("Bob only");
        assertThatThrownBy(() -> tool.execute(context, Map.of("limit", 21))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.execute(context, Map.of("status", "pending"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void completeReturnsServiceResultAndDoesNotCrossOwners() {
        UUID id = UUID.randomUUID();
        when(service.complete("alice", id)).thenReturn(todo(id, "Task", "completed"));
        when(service.complete("bob", id)).thenThrow(new IllegalArgumentException("Todo not found"));
        var tool = new TodoCompleteTool(service);
        assertThat(tool.execute(context, Map.of("id", id.toString())).output())
                .containsEntry("id", id.toString()).containsEntry("status", "completed");
        var bob = new ToolExecutionContext("bob", conversation, UUID.randomUUID(), null, false, ignored -> {});
        assertThatThrownBy(() -> tool.execute(bob, Map.of("id", id.toString()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.execute(context, Map.of("id", "1-1-1-1-1"))).isInstanceOf(IllegalArgumentException.class);
        verify(service, times(1)).complete(eq("alice"), any());
    }
}
