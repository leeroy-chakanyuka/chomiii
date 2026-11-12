package com.chomiii.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProfileBudgetTest {
    private static PersonalMemory memory(String content) {
        return new PersonalMemory(UUID.randomUUID(), content, "user_explicit", Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    void aLargeFactDoesNotPreventALaterShortFactFromFitting() {
        var first = memory("I eat " + "a".repeat(470));
        var tooLong = memory("I eat " + "b".repeat(140));
        var later = memory("I eat rice");

        var paragraph = ProfileComposer.paragraphs(List.of(first, tooLong, later)).getFirst();

        assertThat(paragraph.text()).contains("I eat rice");
        assertThat(paragraph.sourceIds()).containsExactly(first.id(), later.id()).doesNotContain(tooLong.id());
        assertThat(paragraph.text().length()).isLessThanOrEqualTo(ProfileComposer.MAX_PARAGRAPH_CHARS);
    }
}
