package com.chomiii.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlanParserTest {
    private static final UUID MEMORY = UUID.fromString("4d3683e4-fdf6-40bb-babc-81b9ac98a260");
    private static final String VALID = """
            {"goal":"Resolve the meeting conflict","summary":"Find the meeting and propose a change.",
             "memoryReferences":[{"id":"4d3683e4-fdf6-40bb-babc-81b9ac98a260","relevance":"Preferred time"}],
             "constraints":["After ten in the morning"],
             "missingInformation":[{"id":"q1","question":"Which meeting?","kind":"execution","blocks":["s2"]}],
             "steps":[{"id":"s1","title":"Find the meeting","capability":"calendar.search","dependsOn":[]},
                      {"id":"s2","title":"Change the meeting","capability":"calendar.update","dependsOn":["s1"]}]}
            """;
    private final PlanParser parser = new PlanParser();

    private static final String TODO = """
            {"goal":"Plan exam week","summary":"Prepare study tasks.","memoryReferences":[],"constraints":[],
             "missingInformation":[],"steps":[
             {"id":"s1","title":"Make a study task","capability":"todo.create","dependsOn":[],
              "arguments":{"title":"Revise biology","dueAt":"2027-05-01T10:00:00Z","timezone":"Europe/London"}},
             {"id":"s2","title":"List tasks","capability":"todo.list","dependsOn":[],"arguments":{"status":"open","limit":10}},
             {"id":"s3","title":"Complete a task","capability":"todo.complete","dependsOn":[],
              "arguments":{"id":"4d3683e4-fdf6-40bb-babc-81b9ac98a260"}}]}
            """;

    @Test
    void acceptsOnlyDirectListDependenciesForTitleResolution() {
        String dynamic = TODO.replace("\"capability\":\"todo.complete\",\"dependsOn\":[]", "\"capability\":\"todo.complete\",\"dependsOn\":[\"s2\"]")
                .replace("\"id\":\"4d3683e4-fdf6-40bb-babc-81b9ac98a260\"", "\"listStepId\":\"s2\",\"title\":\"Milk\"");
        assertThat(parser.parse(dynamic, Set.of()).steps().get(2).arguments()).containsEntry("listStepId", "s2");
        rejects(dynamic.replace("\"dependsOn\":[\"s2\"]", "\"dependsOn\":[]"));
        rejects(dynamic.replace("\"listStepId\":\"s2\"", "\"listStepId\":\"s1\""));
        rejects(dynamic.replace("\"title\":\"Milk\"", "\"title\":\"Milk\",\"owner\":\"other\""));
    }

    @Test
    void proposesExactImmutableTodoArgumentsWithJavaDerivedState() throws Exception {
        Plan plan = parser.parse(TODO, Set.of());
        assertThat(plan.steps()).extracting(Plan.Step::approvalHint).containsExactly("required", "not_required", "required");
        assertThat(plan.steps()).extracting(Plan.Step::status).containsOnly("planned");
        assertThat(plan.steps().getFirst().arguments()).containsEntry("title", "Revise biology");
        assertThatThrownBy(() -> plan.steps().getFirst().arguments().put("title", "changed"))
                .isInstanceOf(UnsupportedOperationException.class);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(mapper.readValue(mapper.writeValueAsString(plan), Plan.class)).isEqualTo(plan);
        assertThat(new PlanningService().render(plan)).contains("Revise biology");
        assertThat(new Plan.Step("s", "t", "user.prepare", java.util.List.of(), "not_required", "available_for_planning", "planned").arguments())
                .isEqualTo(Map.of());
    }

    @Test
    void rejectsUntrustedOrInvalidTodoArgumentsAndModelState() {
        rejects(TODO.replace("\"title\":\"Revise biology\"", "\"title\":\"Revise biology\",\"owner\":\"other\""));
        rejects(TODO.replace("\"title\":\"Revise biology\"", "\"title\":\"Revise biology\",\"approvalId\":\"x\""));
        rejects(TODO.replace("\"limit\":10", "\"limit\":21"));
        rejects(TODO.replace("\"limit\":10", "\"limit\":\"10\""));
        rejects(TODO.replace("2027-05-01T10:00:00Z", "tomorrow"));
        rejects(TODO.replace("Europe/London", "not/a-zone"));
        rejects(TODO.replace("Revise biology", "   "));
        rejects(TODO.replace("4d3683e4-fdf6-40bb-babc-81b9ac98a260", "not-a-uuid"));
        rejects(TODO.replace("\"goal\":", "\"executed\":true,\"goal\":"));
        rejects(TODO.replace("\"capability\":\"todo.create\"", "\"status\":\"completed\",\"capability\":\"todo.create\""));
        rejects(VALID.replace("\"dependsOn\":[]", "\"dependsOn\":[],\"arguments\":{\"title\":\"x\"}"));
    }

    @Test
    void derivesAvailabilityAndApprovalFromTheCatalog() {
        Plan plan = parser.parse(VALID, Set.of(MEMORY));
        assertThat(plan.id()).isNotNull();
        assertThat(plan.memoryReferences()).singleElement().satisfies(reference -> assertThat(reference.id()).isEqualTo(MEMORY));
        assertThat(plan.steps()).hasSize(2);
        assertThat(plan.steps().getFirst().availability()).isEqualTo("not_connected");
        assertThat(plan.steps().getFirst().approvalHint()).isEqualTo("not_required");
        assertThat(plan.steps().get(1).approvalHint()).isEqualTo("required");
        assertThat(plan.steps().get(1).status()).isEqualTo("blocked");
        assertThat(new PlanningService().render(plan)).contains("needed before a future step", "after step 1");
    }

    @Test
    void rejectsInvalidJsonFieldsAndLimits() {
        rejects("{broken");
        rejects(VALID.replace("Resolve the meeting conflict", "  "));
        rejects(VALID.replace("\"summary\":", "\"unexpected\":true,\"summary\":"));
        rejects(VALID.replace("\"goal\":", "\"goal\":\"extra\",\"goal\":"));
        rejects(VALID + " true");
        rejects(" ".repeat(20_001));
        rejects(VALID.replace("\"Find the meeting\"", "\"Find the meeting\",\"approvalHint\":\"not_required\""));
    }

    @Test
    void rejectsUnknownCapabilitiesAndMemoryReferences() {
        rejects(VALID.replace("calendar.update", "calendar.destroy"));
        assertThatThrownBy(() -> parser.parse(VALID, Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(VALID, Set.of(MEMORY), Set.of("calendar.search")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsObviousClaimsThatAProposedExternalActionAlreadyHappened() {
        rejects(VALID.replace("Find the meeting and propose a change.", "I have already sent the email."));
        rejects(VALID.replace("Find the meeting and propose a change.", "Your meeting has been rescheduled."));
        rejects(VALID.replace("After ten in the morning", "I have already sent the email."));
        rejects(VALID.replace("Preferred time", "I have already sent the email."));
        rejects(VALID.replace("Which meeting?", "Your meeting has been rescheduled."));
    }

    @Test
    void rejectsDuplicateMissingAndCyclicDependencies() {
        rejects(VALID.replace("\"id\":\"s2\",\"title\"", "\"id\":\"s1\",\"title\""));
        rejects(VALID.replace("\"dependsOn\":[\"s1\"]", "\"dependsOn\":[\"missing\"]"));
        rejects(VALID.replace("\"dependsOn\":[]", "\"dependsOn\":[\"s2\"]"));
        rejects(VALID.replace("\"dependsOn\":[\"s1\"]", "\"dependsOn\":[\"s2\"]"));
        rejects(VALID.replace("\"blocks\":[\"s2\"]", "\"blocks\":[\"missing\"]"));
    }

    @Test
    void placesPrerequisitesBeforeDependentStepsEvenIfTheModelReversesThem() throws Exception {
        var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(VALID);
        var steps = (com.fasterxml.jackson.databind.node.ArrayNode) root.path("steps");
        steps.add(steps.remove(0));
        assertThat(steps.get(0).path("id").asText()).isEqualTo("s2");
        assertThat(parser.parse(root.toString(), Set.of(MEMORY)).steps())
                .extracting(Plan.Step::id).containsExactly("s1", "s2");
    }

    private void rejects(String value) {
        assertThatThrownBy(() -> parser.parse(value, Set.of(MEMORY))).isInstanceOf(IllegalArgumentException.class);
    }
}
