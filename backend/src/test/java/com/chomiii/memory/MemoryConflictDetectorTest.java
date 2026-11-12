package com.chomiii.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemoryConflictDetectorTest {
    private static PersonalMemory memory(String content) {
        return new PersonalMemory(UUID.randomUUID(), content, "user_explicit", Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    void differentDestinationsCanHaveDifferentCommutes() {
        var work = List.of(memory("I commute to work by train"));
        assertThat(MemoryConflictDetector.conflicts("I now commute to school by bus", work)).isFalse();
        assertThat(MemoryConflictDetector.conflicts("I now commute to work by bus", work)).isTrue();
        assertThat(MemoryConflictDetector.conflicts("I currently commute to work by bus", work)).isTrue();
        assertThat(MemoryConflictDetector.equivalent("I travel to school by bus",
                List.of(memory("I commute to work by bus")))).isFalse();
    }

    @Test
    void additionalScheduleDetailsAreNotDiscardedAsDuplicates() {
        assertThat(MemoryConflictDetector.equivalent("I currently travel by bus on Sundays",
                List.of(memory("I commute by bus")))).isFalse();
        assertThat(MemoryConflictDetector.equivalent("I currently travel by bus",
                List.of(memory("I commute by bus")))).isTrue();
        assertThat(MemoryConflictDetector.conflicts("I now commute by train on weekends",
                List.of(memory("I commute by bus on weekdays")))).isFalse();
        assertThat(MemoryConflictDetector.equivalent("I live in Harare and study at university",
                List.of(memory("I live in Harare")))).isFalse();
    }

    @Test
    void explicitMealPreferenceChangesAreReviewableWithoutConflatingMeals() {
        var old = List.of(memory("I prefer tacos for dinner"));
        assertThat(MemoryConflictDetector.conflicts("I now prefer noodles for dinner", old)).isTrue();
        assertThat(MemoryConflictDetector.conflicts("I now prefer noodles for lunch", old)).isFalse();
        assertThat(MemoryConflictDetector.conflicts("I now like noodles for dinner", old)).isFalse();
        assertThat(MemoryConflictDetector.conflicts("I now prefer noodles for dinner on weekends",
                List.of(memory("I prefer tacos for dinner on weekdays")))).isFalse();
    }

    @Test
    void explicitNegationDoesNotDisappearAsADuplicate() {
        var old = List.of(memory("I commute by bus"));
        assertThat(MemoryConflictDetector.conflicts("I no longer commute by bus", old)).isTrue();
        assertThat(MemoryConflictDetector.equivalent("I no longer commute by bus", old)).isFalse();
    }
}
