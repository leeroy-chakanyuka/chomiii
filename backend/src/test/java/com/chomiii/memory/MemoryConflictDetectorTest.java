package com.chomiii.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * One generic conflict mechanism for every domain: statements become
 * structured claims (slot, value, scope, change/negation/coexistence markers)
 * and are compared uniformly. Corrections stay reviewable instead of silently
 * coexisting; coexisting facts, differently scoped variants and temporary
 * exceptions stay active; ambiguous change statements are parked for review.
 */
class MemoryConflictDetectorTest {
    private static PersonalMemory memory(String content) {
        return new PersonalMemory(UUID.randomUUID(), content, "user_explicit", Instant.EPOCH, Instant.EPOCH);
    }

    private static PersonalMemory memory(String content, String topic) {
        return new PersonalMemory(UUID.randomUUID(), content, "conversation_fact",
                Instant.EPOCH, Instant.EPOCH, "active", 0.95, null, topic);
    }

    @Test
    void jobChangesAreRecognizedThroughTheGenericSlotMechanism() {
        var old = List.of(memory("I work at the Riverside library"));
        assertThat(MemoryConflictDetector.conflicts("I now work at the Cedar bakery", old)).isTrue();
        assertThat(MemoryConflictDetector.conflicts(
                "I changed jobs and now work at the Cedar bakery", old)).isTrue();
        // A different activity in another slot is not a correction.
        assertThat(MemoryConflictDetector.conflicts("I volunteer at the school library", old)).isFalse();
        assertThat(MemoryConflictDetector.ambiguous("I volunteer at the school library", null, old)).isFalse();
    }

    @Test
    void programmingLanguageChangesAreRecognizedWithoutDomainRules() {
        var old = List.of(memory("I mainly write Python"));
        assertThat(MemoryConflictDetector.conflicts("I now write Rust", old)).isTrue();
        assertThat(MemoryConflictDetector.conflicts("I switched from Python to Rust", old)).isTrue();
        assertThat(MemoryConflictDetector.conflicts("I teach Python on weekends", old)).isFalse();
        assertThat(MemoryConflictDetector.ambiguous("I teach Python on weekends", null, old)).isFalse();
    }

    @Test
    void movingIsReviewableAndNeverSilentlyReplacesAResidence() {
        var old = List.of(memory("I live in Harare"));
        // Without slot evidence the deterministic gate cannot prove the change
        // targets the residence, so the correction is parked for review rather
        // than auto-activated next to the outdated fact.
        assertThat(MemoryConflictDetector.conflicts("I moved to Bulawayo", old)).isFalse();
        assertThat(MemoryConflictDetector.ambiguous("I moved to Bulawayo", null, old)).isTrue();
        // Extraction-supplied topic identity on both records makes it definite.
        assertThat(MemoryConflictDetector.conflicts("I moved to Bulawayo", "residence",
                List.of(memory("I live in Harare", "residence")))).isTrue();
        assertThat(MemoryConflictDetector.conflicts("I moved to Bulawayo", "residence", old)).isFalse();
        // Explicit coexistence is never a correction.
        assertThat(MemoryConflictDetector.conflicts("I also live in Bulawayo", "residence",
                List.of(memory("I live in Harare", "residence")))).isFalse();
        assertThat(MemoryConflictDetector.ambiguous("I also live in Bulawayo", "residence",
                List.of(memory("I live in Harare", "residence")))).isFalse();
    }

    @Test
    void multipleResidencesCoexistWithoutChangeStatements() {
        var homes = List.of(memory("I live in Goodwood"));
        assertThat(MemoryConflictDetector.conflicts("I live in Franschhoek", homes)).isFalse();
        assertThat(MemoryConflictDetector.ambiguous("I live in Franschhoek", "residence", homes)).isFalse();
    }

    @Test
    void mealPreferencesStayMealSpecific() {
        var old = List.of(memory("I prefer tacos for dinner"));
        assertThat(MemoryConflictDetector.conflicts("I now prefer noodles for dinner", old)).isTrue();
        assertThat(MemoryConflictDetector.conflicts("I now prefer noodles for lunch", old)).isFalse();
        // A non-exclusive liking does not displace an exclusive preference.
        assertThat(MemoryConflictDetector.conflicts("I now like noodles for dinner", old)).isFalse();
        assertThat(MemoryConflictDetector.ambiguous("I now like noodles for dinner", null, old)).isTrue();
        assertThat(MemoryConflictDetector.conflicts("I now prefer noodles for dinner on weekends",
                List.of(memory("I prefer tacos for dinner on weekdays")))).isFalse();
    }

    @Test
    void weekdayAndWeekendVariantsCoexistButSameScheduleCorrectionsConflict() {
        var weekdays = List.of(memory("I commute by bus on weekdays"));
        assertThat(MemoryConflictDetector.conflicts("I now commute by train on weekends", weekdays)).isFalse();
        assertThat(MemoryConflictDetector.conflicts("I now commute by train on weekdays", weekdays)).isTrue();
        // A scoped correction against an unscoped fact is ambiguous, not definite.
        var unscoped = List.of(memory("I commute by bus"));
        assertThat(MemoryConflictDetector.conflicts("I now commute by train on weekends", unscoped)).isFalse();
        assertThat(MemoryConflictDetector.ambiguous("I now commute by train on weekends", null, unscoped)).isTrue();
    }

    @Test
    void temporaryExceptionsNeverEraseStandingPreferences() {
        var standing = List.of(memory("I prefer appointments after ten in the morning"));
        assertThat(MemoryConflictDetector.conflicts(
                "For the next two weeks I need appointments before nine", standing)).isFalse();
        assertThat(MemoryConflictDetector.ambiguous(
                "For the next two weeks I need appointments before nine", null, standing)).isFalse();
    }

    @Test
    void explicitNegationDirectlyContradictsTheSameFact() {
        var bus = List.of(memory("I commute by bus"));
        assertThat(MemoryConflictDetector.conflicts("I no longer commute by bus", bus)).isTrue();
        assertThat(MemoryConflictDetector.equivalent("I no longer commute by bus", bus)).isFalse();
        assertThat(MemoryConflictDetector.conflicts(
                "I don't drink coffee anymore", List.of(memory("I drink coffee")))).isTrue();
        // Negation scoped to one occasion is reviewable, not a definite conflict.
        var coffee = List.of(memory("I drink coffee"));
        assertThat(MemoryConflictDetector.conflicts("I don't drink coffee in the morning", coffee)).isFalse();
        assertThat(MemoryConflictDetector.ambiguous("I don't drink coffee in the morning", null, coffee)).isTrue();
    }

    @Test
    void paraphrasedDuplicatesAreSuppressedButAdditionalDetailsAreNot() {
        assertThat(MemoryConflictDetector.equivalent("I travel to work by bus",
                List.of(memory("I commute to work by bus")))).isTrue();
        assertThat(MemoryConflictDetector.equivalent("I travel to school by bus",
                List.of(memory("I commute to work by bus")))).isFalse();
        assertThat(MemoryConflictDetector.equivalent("I currently travel by bus on Sundays",
                List.of(memory("I commute by bus")))).isFalse();
        assertThat(MemoryConflictDetector.equivalent("I currently travel by bus",
                List.of(memory("I commute by bus")))).isTrue();
        assertThat(MemoryConflictDetector.equivalent("I live in Harare and study at university",
                List.of(memory("I live in Harare")))).isFalse();
    }

    @Test
    void differentDestinationsCanHaveDifferentCommutes() {
        var work = List.of(memory("I commute to work by train"));
        assertThat(MemoryConflictDetector.conflicts("I now commute to school by bus", work)).isFalse();
        assertThat(MemoryConflictDetector.conflicts("I now commute to work by bus", work)).isTrue();
        assertThat(MemoryConflictDetector.conflicts("I currently commute to work by bus", work)).isTrue();
    }

    @Test
    void projectStatusChangesAreRecognizedThroughReferences() {
        var project = List.of(memory("My long-term project is learning pottery"));
        assertThat(MemoryConflictDetector.conflicts("I finished the pottery project", project)).isTrue();
        assertThat(MemoryConflictDetector.ambiguous(
                "I am no longer working on the pottery project", null, project)).isTrue();
    }

    @Test
    void ambiguousChangesAreParkedInsteadOfSilentlyActivating() {
        var groceries = List.of(memory("My usual grocery list includes oat milk"));
        assertThat(MemoryConflictDetector.ambiguous("I started learning pottery", null, groceries)).isFalse();
        assertThat(MemoryConflictDetector.conflicts("I started learning pottery", groceries)).isFalse();
        assertThat(MemoryConflictDetector.conflicts(
                "My usual grocery list has dairy milk instead of oat milk", groceries)).isTrue();
        assertThat(MemoryConflictDetector.ambiguous(
                "My usual grocery list has dairy milk instead of oat milk", null, groceries)).isFalse();
    }

    @Test
    void oneResolvedCoexistenceKeepsOtherNewFactsActiveWithoutOverParking() {
        var existing = List.of(
                memory("My usual grocery list includes oat milk"),
                memory("I commute by bus on weekdays"));
        assertThat(MemoryConflictDetector.ambiguous("I now commute by train on weekends", null, existing)).isFalse();
        assertThat(MemoryConflictDetector.conflicts("I now commute by train on weekends", existing)).isFalse();
    }

    @Test
    void sharingAValueDoesNotMakeDifferentFactsConflict() {
        var residence = List.of(memory("I live in Cape Town"));
        assertThat(MemoryConflictDetector.conflicts("I now work in Cape Town", residence)).isFalse();
        assertThat(MemoryConflictDetector.conflicts("I moved to Cape Town", residence)).isFalse();
    }

    @Test
    void aCompletedQualificationDoesNotConflictWithEmploymentInTheSameField() {
        var work = List.of(memory("I work as a dental hygienist at the Brightwater Clinic."));
        String qualification = "I finished my dental hygiene diploma at Colton Community College in 2019.";
        assertThat(MemoryConflictDetector.conflicts(qualification, work)).isFalse();
        assertThat(MemoryConflictDetector.ambiguous(qualification, null, work)).isFalse();
    }

    @Test
    void aCompatibleClauseDoesNotHideAnUnresolvedCorrection() {
        var existing = List.of(memory("I mainly write Python"), memory("I commute by bus on weekdays"));
        String candidate = "I also commute by bus on weekends and I now use Rust";
        assertThat(MemoryConflictDetector.conflicts(candidate, existing)).isFalse();
        assertThat(MemoryConflictDetector.ambiguous(candidate, null, existing)).isTrue();
    }
}
