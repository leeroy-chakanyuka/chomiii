package com.chomiii.todo;

import com.chomiii.tools.InternalTool;
import com.chomiii.tools.ToolDefinition;
import com.chomiii.tools.ToolExecutionContext;
import com.chomiii.tools.ToolSchemaValidator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class TodoCompleteTool implements InternalTool {
    private static final ToolDefinition DEFINITION = new ToolDefinition("todo.complete", "Complete a personal todo.",
            Map.of("type", "object", "properties", Map.of("id", Map.of("type", "string", "pattern",
                    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")),
                    "required", List.of("id"), "additionalProperties", false), "todo.write", ToolDefinition.Effect.WRITE);
    private final TodoService todos;

    public TodoCompleteTool(TodoService todos) { this.todos = todos; }
    public static ToolDefinition toolDefinition() { return DEFINITION; }
    @Override public ToolDefinition definition() { return DEFINITION; }
    @Override public Result execute(ToolExecutionContext context, Map<String, Object> arguments) {
        ToolSchemaValidator.validate(DEFINITION.parameters(), arguments);
        var todo = todos.complete(context.owner(), UUID.fromString((String) arguments.get("id")));
        return new Result(Map.of("id", todo.id().toString(), "status", todo.status()), () -> {});
    }
}
