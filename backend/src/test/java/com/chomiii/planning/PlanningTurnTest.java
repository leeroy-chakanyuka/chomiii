package com.chomiii.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chomiii.approval.ApprovalFacade;
import com.chomiii.tools.InternalTool;
import com.chomiii.tools.ToolCallBudget;
import com.chomiii.tools.ToolDefinition;
import com.chomiii.tools.ToolExecutionContext;
import com.chomiii.tools.ToolRegistry;
import com.chomiii.tools.ToolTurn;
import com.chomiii.tools.trace.ToolTraceDto;
import com.chomiii.tools.trace.ToolTraceService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlanningTurnTest {
    private static final Map<String, Object> PLAN = Map.of("steps", (Object) List.of(
            Map.of("create", Map.of("title", "Milk")), Map.of("create", Map.of("title", "Bread"))));

    @Test
    void stagesOneValidatedPlanWithoutExecutingOrApprovingAnything() {
        var turn = turn(null, null, Set.of());
        assertThat(turn.definitions()).extracting(ToolDefinition::name).contains("plan.stage");
        assertThat(turn.invoke("plan.stage", PLAN)).doesNotContainKey("error");
        assertThat(turn.stagedPlan().executed()).isFalse();
        assertThat(turn.stagedPlan().steps()).hasSize(2).allSatisfy(step -> assertThat(step.approvalHint()).isEqualTo("required"));
        assertThat(turn.definitions()).extracting(ToolDefinition::name).doesNotContain("plan.stage", "todo.create");
        assertThat(turn.invoke("plan.stage", PLAN)).containsEntry("errorCode", "DUPLICATE_CALL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{}", "null", "{\"executed\":true}", "{\"goal\":\"a\",\"goal\":\"b\"}"})
    void malformedOutputIsAToolFailureNotAGenerationFailure(String json) {
        var turn = turn(null, null, Set.of());
        assertThat(turn.invoke("plan.stage", Map.of("steps", List.of(Map.of("create", Map.of("title", "Milk"))), "unexpected", json))).containsEntry("errorCode", "INVALID_ARGUMENTS");
        assertThat(turn.stagedPlan()).isNull();
        assertThat(turn.failed()).isTrue();
    }

    @Test
    void validatesCapabilitiesReferencesArgumentsAndEncodedSize() {
        for (Map<String, Object> invalid : List.<Map<String, Object>>of(
                Map.of("steps", List.of(Map.of("unknown", Map.of()))),
                Map.of("steps", List.of(Map.of("create", Map.of("title", "Milk", "approved", true)))),
                Map.of("steps", List.of(Map.of("create", Map.of("title", "Milk"), "list", Map.of()))),
                Map.of("steps", List.of(Map.of("create", Map.of("title", "é".repeat(2000))))))) {
            var turn = turn(null, null, Set.of());
            assertThat(turn.invoke("plan.stage", invalid)).containsEntry("errorCode", "INVALID_ARGUMENTS");
            assertThat(turn.stagedPlan()).isNull();
        }
        var turn = turn(null, null, Set.of());
        assertThat(turn.invoke("plan.stage", Map.of("steps", PLAN.get("steps"), "owner", "someone-else")))
                .containsEntry("errorCode", "INVALID_ARGUMENTS");
    }

    @Test
    void sharesTheBudgetWithOrdinaryToolsIncludingRejectedCalls() {
        var turn = turn(null, null, Set.of());
        assertThat(turn.invoke("todo.list", Map.of())).doesNotContainKey("error");
        assertThat(turn.invoke("plan.stage", Map.of("steps", List.of()))).containsEntry("errorCode", "INVALID_ARGUMENTS");
        assertThat(turn.invoke("plan.stage", PLAN)).containsEntry("errorCode", "CALL_LIMIT");
        assertThat(turn.stagedPlan()).isNull();
    }

    @Test
    void aPlanCannotBeMixedWithADirectProposalInEitherOrder() {
        var planFirst = turn(null, null, Set.of());
        assertThat(planFirst.invoke("plan.stage", PLAN)).doesNotContainKey("error");
        assertThat(planFirst.invoke("todo.create", Map.of("title", "Milk"))).containsEntry("errorCode", "NOT_AUTHORIZED");

        var approvals = mock(ApprovalFacade.class);
        var traces = mock(ToolTraceService.class);
        var trace = mock(ToolTraceDto.Trace.class);
        when(trace.invocationId()).thenReturn(UUID.randomUUID());
        when(traces.recordRequested(anyString(), any())).thenReturn(trace);
        var directFirst = turn(approvals, traces, Set.of());
        assertThat(directFirst.definitions()).extracting(ToolDefinition::name).doesNotContain("todo.create");
        assertThat(directFirst.invoke("todo.create", Map.of("title", "Milk"))).containsEntry("errorCode", "NOT_AUTHORIZED");
        assertThat(directFirst.invoke("plan.stage", PLAN)).doesNotContainKey("errorCode");
        assertThat(directFirst.stagedPlan().steps()).hasSize(2);
        org.mockito.Mockito.verifyNoInteractions(approvals);
    }

    @Test
    void cancellationPreventsStagingAndAClosedTurnCannotBeReused() {
        var turn = turn(null, null, Set.of());
        turn.close();
        assertThat(turn.invoke("plan.stage", PLAN)).containsEntry("errorCode", "CANCELLED");
        assertThat(turn.stagedPlan()).isNull();
    }

    @Test
    void neverStagesCredentialValuesFromToolArguments() {
        var turn = turn(null, null, Set.of());
        assertThat(turn.invoke("plan.stage", Map.of("steps", List.of(Map.of("create", Map.of("title", "test-key"))))))
                .containsEntry("errorCode", "INVALID_ARGUMENTS");
        assertThat(turn.stagedPlan()).isNull();
    }

    private PlanningTurn turn(ApprovalFacade approvals, ToolTraceService traces, Set<UUID> memoryIds) {
        var budget = new ToolCallBudget();
        var context = new ToolExecutionContext("owner", UUID.randomUUID(), UUID.randomUUID(), null, true, ignored -> {});
        var registry = new ToolRegistry(List.of(tool("todo.list", ToolDefinition.Effect.READ), tool("todo.create", ToolDefinition.Effect.WRITE)));
        return new PlanningTurn(new ToolTurn(registry, context, true, traces, "test", "test", ignored -> {},
                approvals, ignored -> {}, budget), budget, () -> memoryIds, "test-key");
    }

    private InternalTool tool(String name, ToolDefinition.Effect effect) {
        return new InternalTool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition(name, "Test to-do", Map.of("type", "object"),
                        effect == ToolDefinition.Effect.READ ? "todo.read" : "todo.write", effect);
            }
            @Override public Result execute(ToolExecutionContext context, Map<String, Object> arguments) {
                assertThat(context.owner()).isEqualTo("owner");
                assertThat(effect).as("No write can run during generation").isEqualTo(ToolDefinition.Effect.READ);
                return new Result(Map.of("todos", List.of(), "truncated", false), () -> {});
            }
        };
    }
}
