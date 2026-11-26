package com.chomiii.todo;

import com.chomiii.tools.InternalTool;
import com.chomiii.tools.ToolDefinition;
import com.chomiii.tools.ToolExecutionContext;
import com.chomiii.tools.ToolSchemaValidator;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class TodoCreateTool implements InternalTool {
    private static final ToolDefinition DEFINITION = new ToolDefinition("todo.create", "Create a personal todo.",
            Map.of("type", "object", "properties", Map.of(
                    "title", Map.of("type", "string", "minLength", 1, "maxLength", 200),
                    "description", Map.of("type", "string", "maxLength", 4000),
                    "dueAt", Map.of("type", "string", "minLength", 1, "maxLength", 40),
                    "timezone", Map.of("type", "string", "maxLength", 100)),
                    "required", List.of("title"), "additionalProperties", false),
            "todo.write", ToolDefinition.Effect.WRITE);
    private final TodoService todos;

    public TodoCreateTool(TodoService todos) { this.todos = todos; }
    public static ToolDefinition toolDefinition() { return DEFINITION; }
    @Override public ToolDefinition definition() { return DEFINITION; }

    @Override
    public Result execute(ToolExecutionContext context, Map<String, Object> arguments) {
        ToolSchemaValidator.validate(DEFINITION.parameters(), arguments);
        if (context.actionId() == null) throw new IllegalStateException("Trusted action ID is required");
        Instant due = null;
        if (arguments.containsKey("dueAt")) {
            try { due = Instant.parse(((String) arguments.get("dueAt")).strip()); }
            catch (DateTimeParseException invalid) { throw new IllegalArgumentException("Invalid due timestamp", invalid); }
        }
        var request = new TodoService.CreateTodo((String) arguments.get("title"),
                (String) arguments.get("description"), due, (String) arguments.get("timezone"),
                context.conversationId(), context.planId());
        if (context.planId() != null) {
            var result = todos.createWithOutcome(context.owner(), context.actionId(), request);
            return new Result(Map.of("id", result.todo().id().toString(), "status", result.todo().status(),
                    "outcome", result.outcome()), () -> {});
        }
        var todo = todos.create(context.owner(), context.actionId(), request);
        return new Result(Map.of("id", todo.id().toString(), "status", todo.status()), () -> {});
    }
}
