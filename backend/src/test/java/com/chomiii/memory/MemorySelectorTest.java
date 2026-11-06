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
    void recognizesCommonEverydayWordVariations() {
        var shopping = memory("My usual groceries include milk", 12);
        var favourite = memory("My favourite sport is tennis", 13);
        assertThat(MemorySelector.select(List.of(shopping), "Show my usual grocery list"))
                .containsExactly(shopping);
        assertThat(MemorySelector.select(List.of(favourite), "What's my favorite sport?"))
                .containsExactly(favourite);
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

    @Test
    void findsTacoPreferenceWithoutRepeatingTheSameWordsInTheRequest() {
        var food = memory("I like mild tacos with salsa", 12);
        var location = memory("I live in Goodwood", 13);
        var unrelated = memory("I prefer science fiction movies", 14);
        assertThat(MemorySelector.selectForPlanning(List.of(unrelated, location, food),
                "I am hungry and need dinner tonight"))
                .contains(food, location).doesNotContain(unrelated);
    }

    @Test
    void connectsInterviewAndCalendarContextAcrossTopics() {
        var career = memory("I am interviewing for a junior developer job", 13);
        var calendar = memory("I have a meeting on Friday", 14);
        var unrelated = memory("I like tennis", 15);
        assertThat(MemorySelector.selectForPlanning(List.of(unrelated, calendar, career),
                "Please plan around my software interview"))
                .contains(career, calendar).doesNotContain(unrelated);
    }

    @Test
    void genericQueriesStillDoNotLeakUnrelatedPersonalContext() {
        var place = memory("I live in Goodwood", 12);
        var food = memory("I like mild tacos", 13);
        assertThat(MemorySelector.selectForPlanning(List.of(place, food), "Explain a sorting algorithm"))
                .isEmpty();
    }

}
