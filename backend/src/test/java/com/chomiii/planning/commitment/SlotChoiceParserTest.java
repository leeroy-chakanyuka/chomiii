package com.chomiii.planning.commitment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class SlotChoiceParserTest {
    private static final Instant BEFORE_MIDNIGHT = Instant.parse("2026-06-01T23:30:00Z");
    private static final Instant AFTER_MIDNIGHT = Instant.parse("2026-06-02T00:30:00Z");

    @Test
    void anchorsRelativeDateToTheMessageThatMentionedItAndKeepsSelectedTime() {
        var choice = SlotChoiceParser.parse("I'm taking the 1pm slot", AFTER_MIDNIGHT,
                "Let's discuss the museum tomorrow", BEFORE_MIDNIGHT, "Europe/London");
        assertThat(choice).isPresent();
        assertThat(choice.orElseThrow().date()).isEqualTo(LocalDate.of(2026, 6, 3));
        assertThat(choice.orElseThrow().time()).isEqualTo(LocalTime.of(13, 0));
        assertThat(choice.orElseThrow().expiresAt()).isEqualTo(Instant.parse("2026-06-03T23:00:00Z"));
    }

    @Test
    void supportsSameMessageExplicitDateAndActivityWithoutHistory() {
        var choice = SlotChoiceParser.parse("taking the 1:30pm slot for the outing 2026-06-03",
                BEFORE_MIDNIGHT, null, null, "UTC");
        assertThat(choice).isPresent();
        assertThat(choice.orElseThrow().time()).isEqualTo(LocalTime.of(13, 30));
    }

    @Test
    void keepsTheSelectionWhenTheUserAlsoAsksToSeeOpenTodos() {
        var choice = SlotChoiceParser.parse("I'm taking the 1pm slot, then show me all my open to-dos.",
                BEFORE_MIDNIGHT, "Let's discuss the museum tomorrow", BEFORE_MIDNIGHT, "UTC");
        assertThat(choice).isPresent();
        assertThat(choice.orElseThrow().activity()).isEqualTo("museum");
        assertThat(choice.orElseThrow().time()).isEqualTo(LocalTime.of(13, 0));
    }

    @Test
    void abstainsOnAlternativesNegationHypotheticalsMissingMeridiemAndUnknownTimezone() {
        String prior = "Let's discuss the outing tmrw";
        for (String text : new String[] {"I'm taking the 1pm or 2pm slot", "I'm not taking the 1pm slot",
                "I might take the 1pm slot", "taking the 1 slot", "taking the 13:00 slot"}) {
            assertThat(SlotChoiceParser.parse(text, BEFORE_MIDNIGHT, prior, BEFORE_MIDNIGHT, "UTC"))
                    .as(text).isEmpty();
        }
        assertThat(SlotChoiceParser.parse("taking the 1pm slot", BEFORE_MIDNIGHT,
                prior, BEFORE_MIDNIGHT, "Invalid/Zone")).isEmpty();
        assertThat(SlotChoiceParser.parse("taking the 1pm slot", BEFORE_MIDNIGHT,
                "The outing is today or tomorrow", BEFORE_MIDNIGHT, "UTC")).isEmpty();
        assertThat(SlotChoiceParser.parse("taking the 1pm slot", BEFORE_MIDNIGHT,
                "The outing is tomorrow and concerns my medical diagnosis", BEFORE_MIDNIGHT, "UTC")).isEmpty();
    }
}
