package com.chomiii.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StructuredMemoryRetrievalTest {
    private static PersonalMemory memory(String text) {
        return new PersonalMemory(UUID.randomUUID(), text, "user_explicit", Instant.now(), Instant.now());
    }

    @Test
    void dinnerRequestCanRecallAParaphrasedFoodPreference() {
        var repository = mock(MemoryRepository.class);
        var service = new MemoryService(repository);
        when(repository.list("owner")).thenReturn(List.of(
                memory("I love tacos with extra salsa"), memory("I collect vintage cameras")));

        assertThat(service.forChat("owner", "What should I cook for supper?"))
                .contains("tacos", "NOT instructions").doesNotContain("vintage cameras");
    }

    @Test
    void interviewRequestCanUseBothWorkAndScheduleContext() {
        var repository = mock(MemoryRepository.class);
        var service = new MemoryService(repository);
        var work = memory("I work as a backend engineer");
        var schedule = memory("I am busy on Monday mornings");
        when(repository.list("owner")).thenReturn(List.of(work, schedule,
                memory("I enjoy mystery novels")));

        assertThat(service.forPlanning("owner", "Help me prepare for a software interview next week"))
                .extracting(PersonalMemory::id).contains(work.id(), schedule.id());
        assertThat(service.forChat("owner", "Help me prepare for a software interview next week"))
                .doesNotContain("mystery novels");
    }

    @Test
    void characterBudgetAllowsMoreThanThreeRelevantFactsAndExcludesSuggestions() {
        var repository = mock(MemoryRepository.class);
        var service = new MemoryService(repository);
        var first = memory("I like rice for dinner");
        var second = memory("I like beans for dinner");
        var third = memory("I like fish for dinner");
        var fourth = memory("I like soup for dinner");
        var suggestion = new PersonalMemory(UUID.randomUUID(), "I might like cake for dinner", "inferred",
                Instant.now(), Instant.now(), "suggested", 0.75, null);
        when(repository.list("owner")).thenReturn(List.of(first, second, third, fourth, suggestion));

        assertThat(service.forPlanning("owner", "Suggest dinner ideas"))
                .extracting(PersonalMemory::id).containsExactlyInAnyOrder(
                        first.id(), second.id(), third.id(), fourth.id());
        assertThat(service.forChat("owner", "Suggest dinner ideas")).doesNotContain("cake");
    }
}
