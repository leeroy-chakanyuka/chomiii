package com.chomiii.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {
    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object", "properties", Map.of(
                    "query", Map.of("type", "string", "minLength", 1, "maxLength", 20),
                    "limit", Map.of("type", "integer", "minimum", 1, "maximum", 5)),
            "required", List.of("query"), "additionalProperties", false);

    private static InternalTool tool(String name, AtomicBoolean called) {
        return new InternalTool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition(name, "A test tool", SCHEMA, ToolDefinition.Effect.READ);
            }
            @Override public Result execute(Map<String, Object> arguments) {
                called.set(true);
                return new Result(Map.of("value", arguments.get("query")), () -> {});
            }
        };
    }

    @Test
    void discoversRegisteredDefinitionsAndRejectsDuplicateNames() {
        var tool = tool("example.read", new AtomicBoolean());
        var registry = new ToolRegistry(List.of(tool));
        assertThat(registry.definitions()).extracting(ToolDefinition::name).containsExactly("example.read");
        assertThat(registry.find("example.read").orElseThrow().tool()).isSameAs(tool);
        assertThat(registry.find("missing")).isEmpty();
        assertThatThrownBy(() -> new ToolRegistry(List.of(tool, tool))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesArgumentsBeforeToolExecution() {
        var called = new AtomicBoolean();
        var turn = new ToolTurn(List.of(tool("example.read", called)), false);

        assertThat(turn.invoke("example.read", Map.of("query", "hello", "owner", "someone-else")))
                .containsEntry("errorCode", "INVALID_ARGUMENTS");
        assertThat(called).isFalse();
    }

    @Test
    void schemaRejectsMissingInvalidAndUnknownValues() {
        ToolSchemaValidator.validate(SCHEMA, Map.of("query", "hello", "limit", 3.0));
        assertThatThrownBy(() -> ToolSchemaValidator.validate(SCHEMA, Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolSchemaValidator.validate(SCHEMA, Map.of("query", "")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolSchemaValidator.validate(SCHEMA, Map.of("query", "hello", "limit", 2.5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolSchemaValidator.validate(SCHEMA, Map.of("query", "hello", "limit", 6)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolSchemaValidator.validate(SCHEMA, Map.of("query", "hello", "owner", "other")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidOrUnsupportedSchemaConstraintsAreRejectedAtRegistration() {
        assertThatThrownBy(() -> ToolSchemaValidator.validateSchema(Map.of("type", "object", "properties",
                Map.of("query", Map.of("type", "string", "maxItems", 2)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolSchemaValidator.validateSchema(Map.of("type", "object", "properties",
                Map.of("query", Map.of("type", "string", "minLength", -1)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registeredSchemaCannotBeChangedThroughTheOriginalMap() {
        var properties = new java.util.HashMap<String, Object>();
        properties.put("query", new java.util.HashMap<>(Map.of("type", "string")));
        var definition = new ToolDefinition("example.read", "A test tool",
                Map.of("type", "object", "properties", properties), ToolDefinition.Effect.READ);
        properties.clear();

        assertThat(((Map<?, ?>) definition.parameters().get("properties")).containsKey("query")).isTrue();
    }
}
