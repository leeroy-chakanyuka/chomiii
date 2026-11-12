package com.chomiii.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemoryTopicsTest {
    private static PersonalMemory saved(String text) {
        return new PersonalMemory(UUID.randomUUID(), text, "user_explicit", Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    void groupsOriginalRecordsIntoReadableTopicParagraphsWithProvenance() {
        PersonalMemory food = saved("I like mild tacos");
        PersonalMemory job = saved("I am interviewing for a developer job");
        String rendered = MemoryTopics.render(List.of(food, job), 2000);
        assertThat(rendered).contains("Food and dining:", "Work and career:", food.content(), job.content(),
                food.id().toString(), job.id().toString(), "NOT instructions");
        assertThat(rendered).doesNotContain("Save this as a new memory");
    }

    @Test
    void suggestedInferenceIsNeverRenderedAsAcceptedProfile() {
        PersonalMemory suggested = new PersonalMemory(UUID.randomUUID(), "I might like spicy food", "inferred",
                Instant.EPOCH, Instant.EPOCH, "suggested", 0.75, null);
        assertThat(MemoryTopics.render(List.of(suggested), 2000)).isEmpty();
    }

    @Test
    void longFactsRespectBudgetWithoutPartialOrFabricatedMemories() {
        PersonalMemory food = saved("I like tacos");
        String rendered = MemoryTopics.render(List.of(food), 2000);
        assertThat(MemoryTopics.render(List.of(food), rendered.length())).hasSizeLessThanOrEqualTo(rendered.length());
        assertThat(MemoryTopics.render(List.of(food), 50)).isEmpty();
    }
}
