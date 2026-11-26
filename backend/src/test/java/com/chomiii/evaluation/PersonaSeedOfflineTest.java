package com.chomiii.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chomiii.chat.ChatApiException;
import com.chomiii.memory.MemoryService;
import com.chomiii.memory.MemorySearchTool;
import com.chomiii.memory.ProfileSnapshot;
import com.chomiii.planning.PlanningService;
import com.chomiii.provider.ChatProvider;
import com.chomiii.tools.InternalTool;
import com.chomiii.tools.ToolExecutionContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Seeded-recall behavior of the fictional persona against a disposable
 * in-memory store: recall, profile, tool, corrections, learning separation and
 * owner isolation. Database-backed equivalents live in
 * {@link PersonaSeedDatabaseTest}; the profile here uses
 * {@link InMemoryEvaluationProfileRepository} instead of real derived rows.
 */
class PersonaSeedOfflineTest {
    private final MemoryService memories = new MemoryService(
            new InMemoryEvaluationRepository(), new InMemoryEvaluationProfileRepository());

    @Test
    void seededRecallIsVisibleToItsOwnerOnly() {
        var seeded = PersonaSeed.durable(memories, "owner");
        assertThat(seeded).hasSameSizeAs(FictionalPersona.durableAndGoals());
        assertThat(memories.list("owner")).allSatisfy(memory ->
                assertThat(memory.status()).isEqualTo("active"));

        String context = memories.forChat("owner", "Where do I live and what do I cook?");
        assertThat(context).contains("Northgate", "vegetarian", "NOT instructions");
        var dinners = seeded.stream().filter(item -> item.factId().equals("food-dinners"))
                .findFirst().orElseThrow();
        assertThat(context).contains(dinners.memoryId().toString());

        assertThat(memories.list("other")).isEmpty();
        assertThat(memories.forChat("other", "Where do I live and what do I cook?")).isEmpty();
        assertThat(memories.search("other", "appointment", null, 5)).isEmpty();
        assertThat(memories.forPlanning("other", FictionalPersona.planningQuestion())).isEmpty();
        assertThat(memories.profile("other").isEmpty()).isTrue();
        var target = seeded.getFirst().memoryId();
        assertThatThrownBy(() -> memories.delete("other", target))
                .isInstanceOf(ChatApiException.class);
        assertThatThrownBy(() -> memories.replace("other", target, "overwritten by another owner"))
                .isInstanceOf(ChatApiException.class);
        assertThatThrownBy(() -> memories.approve("other", target))
                .isInstanceOf(ChatApiException.class);
        assertThat(memories.list("owner")).anyMatch(memory -> memory.id().equals(target));
    }

    @Test
    void profileComposesSeededLifeAreasAndFollowsCorrections() {
        PersonaSeed.durable(memories, "owner");
        var expectedCategories = List.of("work", "education", "location", "food",
                "schedules", "projects", "preferences");
        ProfileSnapshot profile = memories.profile("owner");
        assertThat(profile.paragraphs()).extracting(paragraph -> paragraph.category())
                .containsAll(expectedCategories);
        assertThat(profile.text()).contains("dental", "Northgate", "Colton", "recipe")
                .hasSizeLessThanOrEqualTo(1400);

        var correction = FictionalPersona.corrections().get(1);
        var outdated = PersonaSeed.fact(memories, "owner", correction.outdatedFact());
        assertThat(memories.profile("owner").text()).contains("Old Dock");
        memories.replace("owner", outdated.memoryId(), correction.corrected());
        assertThat(memories.profile("owner").text()).contains("Millbrook Court").doesNotContain("Old Dock");
        assertThat(memories.forChat("owner", "Where do I live?"))
                .contains("Millbrook Court").doesNotContain("Old Dock");
    }

    @Test
    void memorySearchToolIsOwnerScopedAndTopicFiltered() {
        PersonaSeed.durable(memories, "owner");
        var tool = new MemorySearchTool(memories, null);
        var observed = new ArrayList<ToolExecutionContext.Observation>();
        var context = new ToolExecutionContext("owner", UUID.randomUUID(), UUID.randomUUID(),
                "offline-test-key", true, observed::add);

        assertThat(tool.available(context)).isTrue();
        InternalTool.Result result = tool.execute(context, Map.of("query", "appointment"));
        assertThat(result.output().get("memories").toString()).contains("car service appointment");
        result.onDelivered().run();
        assertThat(observed).hasSize(1);

        var education = tool.execute(context, Map.of("query", "college", "topic", "education"));
        assertThat(education.output().get("memories").toString()).contains("Colton Community College");
        var foodOnly = tool.execute(context, Map.of("query", "college", "topic", "food"));
        assertThat(foodOnly.output().get("memories").toString()).doesNotContain("Colton");

        var other = new ToolExecutionContext("other", UUID.randomUUID(), UUID.randomUUID(),
                "offline-test-key", true, observed::add);
        var foreign = tool.execute(other, Map.of("query", "appointment"));
        assertThat(foreign.output().get("memories").toString())
                .doesNotContain("Brightwater", "car service");

        var optedOut = new ToolExecutionContext("owner", UUID.randomUUID(), UUID.randomUUID(),
                "offline-test-key", false, observed::add);
        assertThat(tool.available(optedOut)).isFalse();
    }

    @Test
    void seededPersonaIsAvailableToTheExistingPlannerAsUntrustedContext() {
        var seeded = PersonaSeed.durable(memories, "owner");
        memories.create("other", "I have a private appointment in another city.");
        var appointment = seeded.stream().filter(item -> item.factId().equals("appointments-car"))
                .findFirst().orElseThrow();
        var selected = memories.forPlanning("owner", FictionalPersona.planningQuestion());
        var planner = new PlanningService();
        var input = planner.input("fake", List.of(), FictionalPersona.planningQuestion(), selected,
                memories.forChat("owner", FictionalPersona.planningQuestion()));
        assertThat(input.systemInstruction()).doesNotContain("car service", "another city");
        assertThat(input.messages().toString()).contains("car service", "NOT instructions").doesNotContain("another city");

        ChatProvider.Generation generation = new ChatProvider.Generation() {
            @Override public void stream(java.util.function.Consumer<String> delta) {
                delta.accept("""
                        {"goal":"Plan the week","summary":"Work around the appointment.",
                         "memoryReferences":[{"id":"%s","relevance":"Car service appointment"}],
                         "constraints":[],"missingInformation":[],
                         "steps":[{"id":"s1","title":"Check the schedule","capability":"user.prepare","dependsOn":[]}]}
                        """.formatted(appointment.memoryId()));
            }
            @Override public void close() { }
        };
        var plan = planner.read(generation, selected);
        assertThat(plan.memoryReferences()).extracting(reference -> reference.id()).contains(appointment.memoryId());
        assertThat(plan.executed()).isFalse();
    }

    @Test
    void learnedFactsStaySeparateFromSeededRecall() {
        PersonaSeed.durable(memories, "owner");
        long revision = memories.revision("owner");
        var conversation = UUID.randomUUID();
        int before = memories.list("owner").size();

        memories.learn("owner", conversation, revision, "I started a weekend pottery class.",
                "conversation_fact", 0.95);

        assertThat(memories.list("owner")).anySatisfy(memory -> {
            assertThat(memory.content()).isEqualTo("I started a weekend pottery class.");
            assertThat(memory.source()).isEqualTo("conversation_fact");
            assertThat(memory.status()).isEqualTo("active");
            assertThat(memory.sourceConversationId()).isEqualTo(conversation);
        });
        assertThat(memories.list("owner")).anySatisfy(memory -> {
            assertThat(memory.source()).isEqualTo("user_explicit");
            assertThat(memory.content()).isEqualTo("I work as a dental hygienist at the Brightwater Clinic.");
        });

        // Re-learning an already seeded statement never duplicates it.
        memories.learn("owner", conversation, revision,
                "I work as a dental hygienist at the Brightwater Clinic.", "conversation_fact", 0.95);
        assertThat(memories.list("owner")).hasSize(before + 1);
    }

    @Test
    void correctionsResolveChangedFactsThroughEditsAndReview() {
        for (var correction : FictionalPersona.corrections()) {
            String owner = "edit-" + correction.id();
            var outdated = PersonaSeed.fact(memories, owner, correction.outdatedFact());
            memories.replace(owner, outdated.memoryId(), correction.corrected());
            assertThat(memories.list(owner)).noneMatch(memory -> memory.content().equals(correction.outdated()));
            assertThat(memories.forChat(owner, recallQuery(correction)))
                    .contains(correction.corrected()).doesNotContain(correction.outdated());
            assertThat(memories.profile(owner).text()).contains(correction.corrected());
        }
        for (var correction : FictionalPersona.corrections()) {
            String owner = "review-" + correction.id();
            var outdated = PersonaSeed.fact(memories, owner, correction.outdatedFact());
            memories.learn(owner, UUID.randomUUID(), memories.revision(owner),
                    correction.corrected(), "conversation_fact", 0.95);
            var suggestion = memories.list(owner).stream()
                    .filter(memory -> memory.content().equals(correction.corrected()))
                    .findFirst().orElseThrow();
            assertThat(suggestion.status()).as(correction.id()).isEqualTo("suggested");
            var approved = memories.approveReplacing(owner, suggestion.id(), outdated.memoryId());
            assertThat(approved.status()).isEqualTo("active");
            assertThat(memories.list(owner)).anySatisfy(memory -> {
                assertThat(memory.id()).isEqualTo(outdated.memoryId());
                assertThat(memory.status()).isEqualTo("superseded");
            });
            assertThat(memories.forChat(owner, recallQuery(correction)))
                    .contains(correction.corrected());
            assertThat(memories.profile(owner).text())
                    .contains(correction.corrected()).doesNotContain(correction.outdated());
        }
    }

    private static String recallQuery(FictionalPersona.Correction correction) {
        return switch (correction.id()) {
            case "correction-commute" -> "Do I take the bus or the train to work?";
            case "correction-residence" -> "Where do I live?";
            default -> "What do I prefer for dinner?";
        };
    }

    @Test
    void ambiguousStatementsRemainSuggestionsUntilApproved() {
        var conversation = UUID.randomUUID();
        var seeded = PersonaSeed.ambiguous(memories, "owner", conversation);
        assertThat(seeded).hasSameSizeAs(FictionalPersona.ambiguous());
        assertThat(memories.list("owner")).filteredOn(memory ->
                        seeded.stream().anyMatch(item -> item.memoryId().equals(memory.id())))
                .allSatisfy(memory -> {
                    assertThat(memory.status()).isEqualTo("suggested");
                    assertThat(memory.source()).isEqualTo("inferred");
                });
        String question = "What is on my schedule?";
        assertThat(memories.forChat("owner", question))
                .doesNotContain("meeting with Sam", "sister also works");
        assertThat(memories.profile("owner").text()).doesNotContain("meeting with Sam");

        var first = seeded.getFirst();
        memories.approve("owner", first.memoryId());
        assertThat(memories.forChat("owner", question)).contains("meeting with Sam");
        assertThat(memories.profile("owner").sourceIds()).contains(first.memoryId());

        // Distractors are never seeded by the helper and never stored here.
        for (var distractor : FictionalPersona.distractors()) {
            assertThat(memories.list("owner")).noneMatch(memory -> memory.content().equals(distractor.statement()));
        }
    }
}
