package com.chomiii.planning;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.chomiii.todo.TodoCreateTool;
import com.chomiii.todo.TodoCompleteTool;
import com.chomiii.todo.TodoListTool;
import com.chomiii.todo.TodoService;
import com.chomiii.tools.ToolDefinition;
import com.chomiii.tools.ToolSchemaValidator;

/** Describes planning capabilities and the explicitly bound local tools. */
public final class CapabilityCatalog {
    public record Capability(String id, String description, String availability, String approvalHint) {}

    private static final List<Capability> ITEMS = List.of(
            new Capability("user.prepare", "Prepare a draft or checklist from supplied information; cannot fetch current data", "available_for_planning", "not_required"),
            new Capability("user.decide", "Ask the person to choose or provide information", "available_for_planning", "not_required"),
            new Capability("todo.create", "Propose a personal todo", "available_for_planning", "required"),
            new Capability("todo.list", "List personal todos", "available_for_planning", "not_required"),
            new Capability("todo.complete", "Propose completing a personal todo", "available_for_planning", "required"),
            new Capability("calendar.search", "Find calendar events", "not_connected", "not_required"),
            new Capability("calendar.availability", "Check calendar availability", "not_connected", "not_required"),
            new Capability("calendar.update", "Change a calendar event", "not_connected", "required"),
            new Capability("gmail.search", "Find messages or recipients", "not_connected", "not_required"),
            new Capability("gmail.send", "Send a message", "not_connected", "required"),
            new Capability("web.search", "Look up current information", "not_connected", "not_required"),
            new Capability("shopping.order", "Place an order", "not_connected", "required"),
            new Capability("reminder.create", "Create a reminder", "not_connected", "required"));
    private static final Map<String, Capability> BY_ID = ITEMS.stream()
            .collect(Collectors.toUnmodifiableMap(Capability::id, Function.identity()));

    private CapabilityCatalog() {}

    public static List<Capability> all() { return ITEMS; }

    public static ToolDefinition toolDefinition(String id) {
        return switch (id) {
            case "todo.create" -> TodoCreateTool.toolDefinition();
            case "todo.list" -> TodoListTool.toolDefinition();
            case "todo.complete" -> TodoCompleteTool.toolDefinition();
            default -> null;
        };
    }

    public static void validateArguments(String id, Map<String, Object> arguments) {
        ToolDefinition tool = toolDefinition(id);
        if (tool == null) {
            if (!arguments.isEmpty()) throw new IllegalArgumentException("Arguments not supported");
            return;
        }
        ToolSchemaValidator.validate(tool.parameters(), arguments);
        if (id.equals("todo.create")) {
            java.time.Instant due = null;
            if (arguments.containsKey("dueAt")) {
                try { due = java.time.Instant.parse(((String) arguments.get("dueAt")).strip()); }
                catch (java.time.DateTimeException invalid) { throw new IllegalArgumentException("Invalid due timestamp", invalid); }
            }
            TodoService.normalize(new TodoService.CreateTodo((String) arguments.get("title"),
                    (String) arguments.get("description"), due, (String) arguments.get("timezone"), null, null));
        }
    }

    public static Capability get(String id) {
        Capability capability = BY_ID.get(id);
        if (capability == null) throw new IllegalArgumentException("Unknown planning capability");
        return capability;
    }
}
