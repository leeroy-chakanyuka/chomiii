package com.chomiii.planning;

import static org.assertj.core.api.Assertions.assertThat;

import com.chomiii.planning.execution.PlanExecution;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlanReplyTest {
    @Test
    void completedReadsReportOnlyTheSavedObservationAndItsLimit() {
        var plan = new PlanParser().parse("""
                {"goal":"Read tasks","summary":"Unverified model text","memoryReferences":[],"constraints":[],"missingInformation":[],
                 "steps":[{"id":"a","title":"Read tasks","capability":"todo.list","dependsOn":[],"arguments":{}}]}
                """, Set.of());
        var observation = new PlanExecution.Observation(List.of(new PlanExecution.Todo(
                UUID.randomUUID().toString(), "Milk [special]", "open")), true);
        var execution = execution(plan, PlanExecution.State.COMPLETED, observation);
        assertThat(PlanReply.result(execution)).contains("Milk \\[special\\] (open)", "limited list")
                .doesNotContain("Unverified model text", "Proposed plan");
    }

    @Test
    void necessaryClarificationIsShownInsteadOfTheInternalPlan() {
        var plan = new PlanParser().parse("""
                {"goal":"Add task","summary":"Internal summary","memoryReferences":[],"constraints":[],
                 "missingInformation":[{"id":"q","question":"Which day works for you?","kind":"execution","blocks":["a"]}],
                 "steps":[{"id":"a","title":"Add milk","capability":"todo.create","dependsOn":[],"arguments":{"title":"Milk"}}]}
                """, Set.of());
        assertThat(PlanReply.result(execution(plan, PlanExecution.State.BLOCKED, null))).isEqualTo("Which day works for you?");
    }

    private PlanExecution execution(Plan plan, PlanExecution.State state, PlanExecution.Observation observation) {
        var step = plan.steps().getFirst();
        return new PlanExecution("owner", UUID.randomUUID(), plan, state, 1, Instant.EPOCH, Instant.EPOCH,
                List.of(new PlanExecution.Step(step.id(), UUID.randomUUID(), step.capability(), state, null,
                        state == PlanExecution.State.COMPLETED ? UUID.randomUUID() : null, null, observation)));
    }
}
