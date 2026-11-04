package com.chomiii.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemorySelectorTest {
    private static PersonalMemory memory(String text, int day) {
        Instant saved = Instant.parse("2026-10-" + String.format("%02d", day) + "T10:00:00Z");
        return new PersonalMemory(UUID.randomUUID(), text, "user_explicit", saved, saved);
    }

    @Test
    void selectsRelevantApprovedFactsInsteadOfSendingEveryMemory() {
        var shopping = memory("My usual groceries are oats, milk and bananas", 12);
        var travel = memory("I prefer Golden Arrow bus transport", 14);
        var unrelated = memory("My favorite sport is football", 15);
        assertThat(MemorySelector.select(List.of(unrelated, travel, shopping), "What are my usual groceries?"))
                .containsExactly(shopping);
    }

    @Test
    void newestMatchingFactsComeFirstAndResultIsBounded() {
        var older = memory("I like coffee in the morning", 11);
        var newer = memory("I like coffee without sugar", 13);
        var third = memory("I drink coffee after lunch", 14);
        var fourth = memory("I drink coffee at the office", 15);
        assertThat(MemorySelector.select(List.of(older, newer, third, fourth), "Tell me about coffee"))
                .containsExactly(fourth, third, newer);
    }

    @Test
    void rejectsNoMatchesAndDoesNotInjectMemoriesForGenericPrompts() {
        var personal = memory("My usual groceries include milk", 12);
        assertThat(MemorySelector.select(List.of(personal), "Explain sorting algorithms")).isEmpty();
        assertThat(MemorySelector.select(List.of(personal), "hello")).isEmpty();
    }

    @Test
    void directRecallQuestionOnlyShowsThreeMostRecent() {
        var a = memory("I prefer tea", 11);
        var b = memory("I play chess", 12);
        var c = memory("I like running", 13);
        var d = memory("I own a bicycle", 14);
        assertThat(MemorySelector.select(List.of(d, c, b, a), "What do you remember about me?"))
                .containsExactly(d, c, b);
    }
}
