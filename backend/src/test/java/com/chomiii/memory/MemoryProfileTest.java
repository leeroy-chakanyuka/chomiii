package com.chomiii.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chomiii.memory.MemoryProfileRepository.ProfileParagraphRow;
import com.chomiii.memory.MemoryProfileRepository.ProfileState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Observable behavior of the derived, owner-scoped personal profile: concise
 * human-readable category paragraphs composed deterministically from approved
 * active canonical memories, each citing the canonical memory IDs it was built
 * from. Canonical memories remain authoritative; any canonical change
 * (create/replace/approve/delete/clear/learn) refreshes the derived rows
 * synchronously inside the mutation transaction under the existing owner lock.
 */
class MemoryProfileTest {

    private static PersonalMemory active(UUID id, String content) {
        return new PersonalMemory(id, content, "user_explicit", Instant.now(), Instant.now());
    }

    private static PersonalMemory learned(UUID id, String content, String status, UUID conversation) {
        return new PersonalMemory(id, content, "conversation_fact", Instant.now(), Instant.now(),
                status, 0.95, conversation);
    }

    @Test
    void profileIsBuiltLazilyFromApprovedActiveMemoriesAndCitesTheirCanonicalIds() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryProfileRepository profiles = mock(MemoryProfileRepository.class);
        MemoryService service = new MemoryService(repo, profiles);
        UUID food = UUID.randomUUID();
        UUID work = UUID.randomUUID();
        when(repo.list("owner")).thenReturn(List.of(
                active(food, "I usually drink flat whites with oat milk"),
                active(work, "I work as a nurse at St Mary's hospital")));
        when(repo.revision("owner")).thenReturn(4L);
        // Never built yet (pre-migration owner): the first read builds lazily.
        when(profiles.state("owner")).thenReturn(null).thenReturn(
                new ProfileState(2L, 2L, 4L,
                        "Food: I usually drink flat whites with oat milk.\n"
                                + "Work: I work as a nurse at St Mary's hospital."));
        when(profiles.paragraphs("owner")).thenReturn(List.of()).thenReturn(List.of(
                new ProfileParagraphRow("food", 0, "I usually drink flat whites with oat milk.",
                        4L, 2L, 1, List.of(food)),
                new ProfileParagraphRow("work", 1, "I work as a nurse at St Mary's hospital.",
                        4L, 2L, 1, List.of(work))));
        when(profiles.contentRevision("owner")).thenReturn(2L);

        ProfileSnapshot snapshot = service.profile("owner");

        // Lazy creation happens under the owner lock, atomically with the write.
        verify(repo).lockOwner("owner");
        verify(profiles).ensureRow("owner");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProfileParagraph>> built =
                ArgumentCaptor.forClass((Class<List<ProfileParagraph>>) (Class<?>) List.class);
        verify(profiles).replaceParagraphs(eq("owner"), anyString(), eq(4L), eq(2L), built.capture());
        assertThat(built.getValue()).extracting(ProfileParagraph::category)
                .containsExactly("food", "work");
        assertThat(built.getValue()).extracting(ProfileParagraph::text)
                .contains("I usually drink flat whites with oat milk.",
                        "I work as a nurse at St Mary's hospital.");
        assertThat(built.getValue().get(0).sourceIds()).containsExactly(food);
        assertThat(built.getValue().get(1).sourceIds()).containsExactly(work);

        // The returned snapshot is human-readable and cites canonical source IDs.
        assertThat(snapshot.text()).contains("flat whites").contains("nurse");
        assertThat(snapshot.paragraphs()).extracting(ProfileParagraph::category)
                .containsExactly("food", "work");
        assertThat(snapshot.sourceIds()).containsExactlyInAnyOrder(food, work);
        verify(profiles, never()).bumpContentRevision(anyString());
    }

    @Test
    void aFreshProfileIsReadWithoutRebuilding() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryProfileRepository profiles = mock(MemoryProfileRepository.class);
        MemoryService service = new MemoryService(repo, profiles);
        UUID food = UUID.randomUUID();
        when(repo.list("owner")).thenReturn(List.of(active(food, "I usually eat oats for breakfast")));
        when(repo.revision("owner")).thenReturn(4L);
        when(profiles.state("owner")).thenReturn(
                new ProfileState(2L, 2L, 4L, "Food: I usually eat oats for breakfast."));
        when(profiles.paragraphs("owner")).thenReturn(List.of(
                new ProfileParagraphRow("food", 0, "I usually eat oats for breakfast.",
                        4L, 2L, 1, List.of(food))));

        ProfileSnapshot snapshot = service.profile("owner");

        // The owner lock is taken before any reads; the stored snapshot is
        // served without any rebuild work.
        verify(repo).lockOwner("owner");
        assertThat(snapshot.text()).isEqualTo("Food: I usually eat oats for breakfast.");
        assertThat(snapshot.paragraphs()).hasSize(1);
        assertThat(snapshot.paragraphs().get(0).sourceIds()).containsExactly(food);
        verify(profiles, never()).replaceParagraphs(anyString(), anyString(), anyLong(), anyLong(), anyList());
        verify(profiles, never()).ensureRow(anyString());
    }

    @Test
    void theOwnerLockIsTakenBeforeAnyProfileOrCanonicalRead() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryProfileRepository profiles = mock(MemoryProfileRepository.class);
        MemoryService service = new MemoryService(repo, profiles);
        UUID food = UUID.randomUUID();
        when(repo.list("owner")).thenReturn(List.of(active(food, "I usually eat oats for breakfast")));
        when(repo.revision("owner")).thenReturn(4L);
        when(profiles.state("owner")).thenReturn(
                new ProfileState(2L, 2L, 4L, "Food: I usually eat oats for breakfast."));
        when(profiles.paragraphs("owner")).thenReturn(List.of(
                new ProfileParagraphRow("food", 0, "I usually eat oats for breakfast.",
                        4L, 2L, 1, List.of(food))));

        service.profile("owner");

        // Under MySQL REPEATABLE READ, a consistent-read snapshot is fixed by
        // the first read: locking the owner row first guarantees a committed
        // correction is visible and cannot be overwritten by stale reads.
        org.mockito.InOrder ordered = org.mockito.Mockito.inOrder(repo, profiles);
        ordered.verify(repo).lockOwner("owner");
        ordered.verify(profiles).state("owner");
        ordered.verify(profiles).paragraphs("owner");
        ordered.verify(repo).list("owner");
        ordered.verify(repo).revision("owner");
        verify(profiles, never()).replaceParagraphs(anyString(), anyString(), anyLong(), anyLong(), anyList());
    }

    /**
     * A paragraph that survives with a citation to a memory that no longer
     * exists (for example removed outside the service) must never be served:
     * the read rebuilds it from the authoritative canonical rows first.
     */
    @Test
    void aSurvivingParagraphCitingAMissingSourceIsRebuiltBeforeUse() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryProfileRepository profiles = mock(MemoryProfileRepository.class);
        MemoryService service = new MemoryService(repo, profiles);
        UUID missing = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        when(repo.list("owner")).thenReturn(List.of(active(kept, "I work as a nurse at St Mary's hospital")));
        when(repo.revision("owner")).thenReturn(7L);
        // Revision numbers claim freshness, but the row cites a deleted memory.
        when(profiles.state("owner")).thenReturn(new ProfileState(2L, 2L, 7L, "Food: deleted text."));
        when(profiles.paragraphs("owner")).thenReturn(List.of(
                new ProfileParagraphRow("food", 0, "deleted text", 7L, 2L, 1, List.of(missing))));
        when(profiles.contentRevision("owner")).thenReturn(2L);

        service.profile("owner");

        verify(repo).lockOwner("owner");
        verify(profiles).replaceParagraphs(eq("owner"), anyString(), eq(7L), eq(2L), anyList());
    }

    @Test
    void anActiveLearnedInsertionBumpsTheContentRevisionAndRefreshesTheProfile() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryProfileRepository profiles = mock(MemoryProfileRepository.class);
        MemoryService service = new MemoryService(repo, profiles);
        UUID conversation = UUID.randomUUID();
        UUID learnedId = UUID.randomUUID();
        when(repo.revision("owner")).thenReturn(3L);
        when(repo.count("owner")).thenReturn(0);
        when(repo.existsContent("owner", "I prefer weekday early walks")).thenReturn(false);
        when(repo.list("owner")).thenReturn(List.of(
                learned(learnedId, "I prefer weekday early walks", "active", conversation)));
        when(profiles.contentRevision("owner")).thenReturn(1L);

        service.learn("owner", conversation, 3L, "I prefer weekday early walks",
                "conversation_fact", 0.95);

        verify(repo).createLearned("owner", conversation, "I prefer weekday early walks",
                "conversation_fact", "active", 0.95);
        // Successful learned insertions advance a separate content revision;
        // the stale-extraction gate (memory_revision) is untouched.
        verify(profiles).ensureRow("owner");
        verify(profiles).bumpContentRevision("owner");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProfileParagraph>> built =
                ArgumentCaptor.forClass((Class<List<ProfileParagraph>>) (Class<?>) List.class);
        verify(profiles).replaceParagraphs(eq("owner"), anyString(), eq(3L), eq(1L), built.capture());
        assertThat(built.getValue()).hasSize(1);
        assertThat(built.getValue().get(0).text()).contains("weekday early walks");
        assertThat(built.getValue().get(0).sourceIds()).containsExactly(learnedId);
    }

    @Test
    void suggestedLearnedMemoriesNeverEnterTheDerivedProfile() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryProfileRepository profiles = mock(MemoryProfileRepository.class);
        MemoryService service = new MemoryService(repo, profiles);
        UUID conversation = UUID.randomUUID();
        UUID suggestedId = UUID.randomUUID();
        when(repo.revision("owner")).thenReturn(3L);
        when(repo.count("owner")).thenReturn(0);
        when(repo.existsContent("owner", "I might like early afternoon walks")).thenReturn(false);
        when(repo.list("owner")).thenReturn(List.of(
                learned(suggestedId, "I might like early afternoon walks", "suggested", conversation)));
        when(profiles.contentRevision("owner")).thenReturn(1L);

        service.learn("owner", conversation, 3L, "I might like early afternoon walks",
                "inferred", 0.76);

        verify(profiles).bumpContentRevision("owner");
        verify(profiles).replaceParagraphs(eq("owner"), eq(""), eq(3L), eq(1L), eq(List.of()));
    }

    @Test
    void deletingAMemoryImmediatelyRemovesItsTextAndCitationFromTheProfile() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryProfileRepository profiles = mock(MemoryProfileRepository.class);
        MemoryService service = new MemoryService(repo, profiles);
        UUID deleted = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        when(repo.delete("owner", deleted)).thenReturn(true);
        when(repo.revision("owner")).thenReturn(9L);
        when(repo.list("owner")).thenReturn(List.of(active(kept, "I work as a nurse at St Mary's hospital")));
        when(profiles.contentRevision("owner")).thenReturn(5L);

        service.delete("owner", deleted);

        verify(repo).lockOwner("owner");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProfileParagraph>> built =
                ArgumentCaptor.forClass((Class<List<ProfileParagraph>>) (Class<?>) List.class);
        verify(profiles).replaceParagraphs(eq("owner"), anyString(), eq(9L), eq(5L), built.capture());
        assertThat(built.getValue()).extracting(ProfileParagraph::sourceIds)
                .flatExtracting(ids -> ids)
                .doesNotContain(deleted);
        assertThat(built.getValue()).extracting(ProfileParagraph::text)
                .noneMatch(text -> text.contains("flat whites"));
    }

    @Test
    void clearingAllMemoriesProducesAnEmptyProfile() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryProfileRepository profiles = mock(MemoryProfileRepository.class);
        MemoryService service = new MemoryService(repo, profiles);
        when(repo.clear("owner")).thenReturn(2);
        when(repo.revision("owner")).thenReturn(10L);
        when(repo.list("owner")).thenReturn(List.of());
        when(profiles.contentRevision("owner")).thenReturn(6L);

        service.clear("owner");

        verify(profiles).replaceParagraphs(eq("owner"), eq(""), eq(10L), eq(6L), eq(List.of()));
    }

    @Test
    void obviousSecretsAreNeverPersistedIntoDerivedProfileText() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryProfileRepository profiles = mock(MemoryProfileRepository.class);
        MemoryService service = new MemoryService(repo, profiles);
        UUID secret = UUID.randomUUID();
        UUID normal = UUID.randomUUID();
        when(repo.list("owner")).thenReturn(List.of(
                active(secret, "My api_key is sk-live-abcdef123456"),
                active(normal, "I like science fiction films")));
        when(repo.revision("owner")).thenReturn(4L);
        when(profiles.state("owner")).thenReturn(null).thenReturn(
                new ProfileState(1L, 1L, 4L, "Preferences: I like science fiction films."));
        when(profiles.paragraphs("owner")).thenReturn(List.of()).thenReturn(List.of(
                new ProfileParagraphRow("preferences", 0, "I like science fiction films.",
                        4L, 1L, 1, List.of(normal))));
        when(profiles.contentRevision("owner")).thenReturn(1L);

        service.profile("owner");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProfileParagraph>> built =
                ArgumentCaptor.forClass((Class<List<ProfileParagraph>>) (Class<?>) List.class);
        verify(profiles).replaceParagraphs(eq("owner"), anyString(), eq(4L), eq(1L), built.capture());
        assertThat(built.getValue()).extracting(ProfileParagraph::text)
                .noneMatch(text -> text.contains("sk-live-abcdef123456"));
        assertThat(built.getValue()).extracting(ProfileParagraph::sourceIds)
                .flatExtracting(ids -> ids)
                .doesNotContain(secret);
        assertThat(built.getValue().get(0).sourceIds()).containsExactly(normal);
    }

    @Test
    void anUnavailableMemoryStoreFailsLoudlyInsteadOfReportingAnEmptyProfile() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryProfileRepository profiles = mock(MemoryProfileRepository.class);
        MemoryService service = new MemoryService(repo, profiles);
        when(profiles.state("owner")).thenThrow(new DataAccessResourceFailureException("database offline"));

        // The error must propagate out of the transaction (the chat caller
        // catches transaction and data exceptions): swallowing it inside a
        // write transaction could report success over a rolled-back write.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.profile("owner"))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verify(repo).lockOwner("owner");
    }

    /**
     * A cascaded deletion (a canonical memory row removed outside the service)
     * can strand a paragraph with zero source rows while the revision stamps
     * still claim freshness. Such a paragraph must never be served: the read
     * rebuilds it from the authoritative canonical rows first.
     */
    @Test
    void aParagraphStrandedWithoutSourcesByCascadeDeletionIsRebuiltBeforeUse() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryProfileRepository profiles = mock(MemoryProfileRepository.class);
        MemoryService service = new MemoryService(repo, profiles);
        when(repo.list("owner")).thenReturn(List.of());
        when(repo.revision("owner")).thenReturn(7L);
        when(profiles.state("owner")).thenReturn(new ProfileState(2L, 2L, 7L, "Food: deleted text."));
        when(profiles.paragraphs("owner")).thenReturn(List.of(
                new ProfileParagraphRow("food", 0, "deleted text", 7L, 2L, 1, List.of())));
        when(profiles.contentRevision("owner")).thenReturn(2L);

        service.profile("owner");

        verify(repo).lockOwner("owner");
        verify(profiles).replaceParagraphs(eq("owner"), eq(""), eq(7L), eq(2L), eq(List.of()));
    }

    @Test
    void profileReadsAndRebuildsStayScopedToTheExactOwner() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryProfileRepository profiles = mock(MemoryProfileRepository.class);
        MemoryService service = new MemoryService(repo, profiles);
        when(profiles.state("alice")).thenReturn(null);
        when(repo.list("alice")).thenReturn(List.of());
        when(repo.revision("alice")).thenReturn(0L);
        when(profiles.contentRevision("alice")).thenReturn(0L);

        service.profile("alice");

        verify(profiles, org.mockito.Mockito.atLeastOnce()).state("alice");
        verify(repo, org.mockito.Mockito.atLeastOnce()).list("alice");
        verify(profiles).replaceParagraphs(eq("alice"), eq(""), eq(0L), eq(0L), eq(List.of()));
        verify(profiles, never()).state("bob");
        verify(repo, never()).list("bob");
        verify(profiles, never()).replaceParagraphs(eq("bob"), anyString(), anyLong(), anyLong(), anyList());
    }

    @Test
    void anEmptyOwnerProfileIsRepresentable() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryProfileRepository profiles = mock(MemoryProfileRepository.class);
        MemoryService service = new MemoryService(repo, profiles);
        when(repo.list("owner")).thenReturn(List.of());
        when(repo.revision("owner")).thenReturn(1L);
        when(profiles.state("owner")).thenReturn(new ProfileState(0L, 0L, 1L, ""));
        when(profiles.paragraphs("owner")).thenReturn(List.of());

        ProfileSnapshot snapshot = service.profile("owner");

        assertThat(snapshot.text()).isEmpty();
        assertThat(snapshot.paragraphs()).isEmpty();
        assertThat(snapshot.sourceIds()).isEmpty();
    }

    @Test
    void paragraphsAreComposedDirectlyFromMemoryTextWithinBounds() {
        List<PersonalMemory> memories = new java.util.ArrayList<>();
        for (int index = 0; index < 10; index++) {
            memories.add(active(UUID.randomUUID(),
                    "I eat oats and milk for breakfast number " + index + " every single day"));
        }
        List<ProfileParagraph> paragraphs = ProfileComposer.paragraphs(memories);

        assertThat(paragraphs).hasSize(1);
        assertThat(paragraphs.get(0).category()).isEqualTo("food");
        assertThat(paragraphs.get(0).text().length()).isLessThanOrEqualTo(ProfileComposer.MAX_PARAGRAPH_CHARS);
        assertThat(ProfileComposer.snapshot(paragraphs).length())
                .isLessThanOrEqualTo(ProfileComposer.MAX_SNAPSHOT_CHARS);
        assertThat(paragraphs.get(0).text()).startsWith("I eat oats and milk");
    }

    @Test
    void categoryClassificationIsDeterministicAndRuleBased() {
        assertThat(ProfileComposer.categoryOf("My usual groceries are oats and milk")).isEqualTo("food");
        assertThat(ProfileComposer.categoryOf("I usually drink flat whites with oat milk")).isEqualTo("food");
        assertThat(ProfileComposer.categoryOf("I live in Harare near the city centre")).isEqualTo("location");
        assertThat(ProfileComposer.categoryOf("I work night shifts at the hospital")).isEqualTo("work");
        assertThat(ProfileComposer.categoryOf("I study computer science at university")).isEqualTo("education");
        assertThat(ProfileComposer.categoryOf("I go to the gym every weekday morning")).isEqualTo("schedules");
        assertThat(ProfileComposer.categoryOf("I am building a small watering app on the side")).isEqualTo("projects");
        assertThat(ProfileComposer.categoryOf("I prefer window seats on long flights")).isEqualTo("preferences");
        assertThat(ProfileComposer.categoryOf("My sister's name is Rudo")).isEqualTo("other");
        // The same content always classifies the same way: no model inference.
        String ambiguous = "I like vegetarian cooking classes";
        assertThat(ProfileComposer.categoryOf(ambiguous))
                .isEqualTo(ProfileComposer.categoryOf(ambiguous))
                .isEqualTo("food");
    }

    @Test
    void emptyCanonicalMemoriesComposeAnEmptyDerivedProfile() {
        assertThat(ProfileComposer.paragraphs(List.of())).isEmpty();
        assertThat(ProfileComposer.snapshot(List.of())).isEmpty();
    }

    /** Exactly 100-character food sentences so the char budget math is exact. */
    private static String foodSentence(int number) {
        String base = "I eat oats and milk for breakfast " + number;
        return base + "x".repeat(100 - base.length());
    }

    /** Punctuation-free facts become complete sentences; punctuation is kept. */
    @Test
    void paragraphJoinsEachIncludedFactAsACompleteSentence() {
        UUID tacos = UUID.randomUUID();
        UUID salsa = UUID.randomUUID();
        UUID oats = UUID.randomUUID();

        List<ProfileParagraph> paragraphs = ProfileComposer.paragraphs(List.of(
                active(tacos, "I eat tacos on tuesdays"),
                active(salsa, "I prefer extra salsa with my oats"),
                active(oats, "I drink oat milk.")));

        assertThat(paragraphs).hasSize(1);
        ProfileParagraph paragraph = paragraphs.get(0);
        assertThat(paragraph.category()).isEqualTo("food");
        assertThat(paragraph.text()).isEqualTo(
                "I eat tacos on tuesdays. I prefer extra salsa with my oats. I drink oat milk.");
        assertThat(paragraph.sourceIds()).containsExactly(tacos, salsa, oats);
        assertThat(paragraph.text().length()).isLessThanOrEqualTo(ProfileComposer.MAX_PARAGRAPH_CHARS);
    }

    /**
     * The paragraph bound includes inserted punctuation and separators: facts
     * are admitted only while the finished sentence-joined text still fits,
     * and omitted facts are not cited.
     */
    @Test
    void aParagraphNeverExceedsTheMaximumIncludingInsertedPunctuation() {
        UUID oats = UUID.randomUUID();
        UUID salsa = UUID.randomUUID();
        UUID tacos = UUID.randomUUID();
        String punctuated = "I eat oats and milk."; // 20 chars, already a sentence
        String base = "I prefer salsa over my usual oats ";
        String unpunctuated = base + "x".repeat(578 - base.length()); // 578 -> 579 with "."

        List<ProfileParagraph> paragraphs = ProfileComposer.paragraphs(List.of(
                active(oats, punctuated),
                active(salsa, unpunctuated),
                active(tacos, "I eat tacos with salsa.")));

        assertThat(paragraphs).hasSize(1);
        ProfileParagraph paragraph = paragraphs.get(0);
        // 20 + 1 separator + 579 = exactly the 600-character maximum.
        assertThat(paragraph.text().length()).isEqualTo(ProfileComposer.MAX_PARAGRAPH_CHARS);
        assertThat(paragraph.text()).isEqualTo(punctuated + " " + unpunctuated + ".");
        assertThat(paragraph.sourceIds()).containsExactly(oats, salsa);
        assertThat(paragraph.sourceIds()).doesNotContain(tacos);
        assertThat(paragraph.text()).doesNotContain("tacos");
    }

    /**
     * When the paragraph char budget omits later memories, their citations
     * must be omitted too: a paragraph cites exactly the memories whose text
     * it actually contains, and composing twice from the same canonical rows
     * is identical.
     */
    @Test
    void anOverflowingParagraphCitesOnlyTheMemoriesItsTextActuallyContains() {
        List<PersonalMemory> memories = new java.util.ArrayList<>();
        UUID[] ids = new UUID[6];
        for (int number = 1; number <= 6; number++) {
            ids[number - 1] = UUID.randomUUID();
            memories.add(active(ids[number - 1], foodSentence(number)));
        }

        List<ProfileParagraph> paragraphs = ProfileComposer.paragraphs(memories);

        assertThat(paragraphs).hasSize(1);
        ProfileParagraph paragraph = paragraphs.get(0);
        // 5 sentences of 100 chars (plus separators) fit the 600-char budget;
        // the sixth is omitted from both text and citations.
        assertThat(paragraph.sourceIds()).containsExactly(
                ids[0], ids[1], ids[2], ids[3], ids[4]);
        assertThat(paragraph.text()).contains(foodSentence(5)).doesNotContain(foodSentence(6));
        assertThat(paragraph.text().length()).isLessThanOrEqualTo(ProfileComposer.MAX_PARAGRAPH_CHARS);
        assertThat(ProfileComposer.paragraphs(memories)).isEqualTo(paragraphs);
    }

    /**
     * Omission by the char budget is by design, so a stored profile that cites
     * only the included memories must be served without rebuilding, and its
     * snapshot-level source IDs stay truthful to the text it contains.
     */
    @Test
    void aProfileThatOmittedMemoriesForTheCharBudgetIsNotRebuiltOnEveryRead() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryProfileRepository profiles = mock(MemoryProfileRepository.class);
        MemoryService service = new MemoryService(repo, profiles);
        UUID[] ids = new UUID[6];
        List<PersonalMemory> memories = new java.util.ArrayList<>();
        for (int number = 1; number <= 6; number++) {
            ids[number - 1] = UUID.randomUUID();
            memories.add(active(ids[number - 1], foodSentence(number)));
        }
        // Each included fact is stored as its own complete sentence.
        String includedText = java.util.List.of(1, 2, 3, 4, 5).stream()
                .map(number -> foodSentence(number) + ".")
                .collect(java.util.stream.Collectors.joining(" "));
        List<UUID> cited = List.of(ids[0], ids[1], ids[2], ids[3], ids[4]);
        when(repo.list("owner")).thenReturn(memories);
        when(repo.revision("owner")).thenReturn(4L);
        when(profiles.state("owner")).thenReturn(
                new ProfileState(2L, 2L, 4L, "Food: " + includedText));
        when(profiles.paragraphs("owner")).thenReturn(List.of(
                new ProfileParagraphRow("food", 0, includedText, 4L, 2L, 5, cited)));

        ProfileSnapshot first = service.profile("owner");
        ProfileSnapshot second = service.profile("owner");

        assertThat(first.text()).isEqualTo("Food: " + includedText);
        assertThat(first.sourceIds()).containsExactlyInAnyOrder(cited.toArray(UUID[]::new));
        assertThat(first.sourceIds()).doesNotContain(ids[5]);
        assertThat(second.sourceIds()).isEqualTo(first.sourceIds());
        verify(repo, org.mockito.Mockito.times(2)).lockOwner("owner");
        verify(profiles, never()).replaceParagraphs(anyString(), anyString(), anyLong(), anyLong(), anyList());
        verify(profiles, never()).ensureRow(anyString());
        verify(profiles, never()).bumpContentRevision(anyString());
    }

    /**
     * Paragraphs persist an explicit position in composed order; reads must
     * reproduce that order (not an alphabetical one) so the saved snapshot
     * text keeps matching. The three categories here follow the nonalphabetical
     * composer declaration order food, work, schedules while the rows come
     * back scrambled, as a database join could return them.
     */
    @Test
    void storedParagraphPositionsKeepTheComposedSnapshotOrder() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryProfileRepository profiles = mock(MemoryProfileRepository.class);
        MemoryService service = new MemoryService(repo, profiles);
        UUID foodId = UUID.randomUUID();
        UUID workId = UUID.randomUUID();
        UUID schedulesId = UUID.randomUUID();
        when(repo.list("owner")).thenReturn(List.of(
                active(workId, "I work as a nurse at the hospital"),
                active(foodId, "I eat oats and milk for breakfast"),
                active(schedulesId, "I go to the gym every weekday morning")));
        when(repo.revision("owner")).thenReturn(4L);
        String snapshotText = "Food: I eat oats and milk for breakfast.\n"
                + "Work: I work as a nurse at the hospital.\n"
                + "Schedules: I go to the gym every weekday morning.";
        when(profiles.state("owner")).thenReturn(new ProfileState(2L, 2L, 4L, snapshotText));
        when(profiles.paragraphs("owner")).thenReturn(List.of(
                new ProfileParagraphRow("schedules", 2, "I go to the gym every weekday morning.",
                        4L, 2L, 1, List.of(schedulesId)),
                new ProfileParagraphRow("food", 0, "I eat oats and milk for breakfast.",
                        4L, 2L, 1, List.of(foodId)),
                new ProfileParagraphRow("work", 1, "I work as a nurse at the hospital.",
                        4L, 2L, 1, List.of(workId))));

        ProfileSnapshot snapshot = service.profile("owner");

        assertThat(snapshot.text()).isEqualTo(snapshotText);
        assertThat(snapshot.paragraphs()).extracting(ProfileParagraph::category)
                .containsExactly("food", "work", "schedules");
        assertThat(snapshot.sourceIds()).containsExactly(foodId, workId, schedulesId);
        verify(profiles, never()).replaceParagraphs(anyString(), anyString(), anyLong(), anyLong(), anyList());
    }

    /**
     * A cascade-deleted source relationship leaves a paragraph with fewer
     * surviving sources than were recorded at build time while the revision
     * stamps still claim freshness. The count mismatch must trigger a rebuild
     * before the paragraph is served.
     */
    @Test
    void aParagraphWhoseSurvivingSourceCountNoLongerMatchesIsRebuiltBeforeUse() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryProfileRepository profiles = mock(MemoryProfileRepository.class);
        MemoryService service = new MemoryService(repo, profiles);
        UUID surviving = UUID.randomUUID();
        when(repo.list("owner")).thenReturn(List.of(active(surviving, "I eat oats and milk for breakfast")));
        when(repo.revision("owner")).thenReturn(7L);
        when(profiles.state("owner")).thenReturn(
                new ProfileState(2L, 2L, 7L, "Food: I eat oats and milk for breakfast."));
        when(profiles.paragraphs("owner")).thenReturn(List.of(
                new ProfileParagraphRow("food", 0, "I eat oats and milk for breakfast.",
                        7L, 2L, 2, List.of(surviving))));
        when(profiles.contentRevision("owner")).thenReturn(2L);

        service.profile("owner");

        verify(repo).lockOwner("owner");
        verify(profiles).replaceParagraphs(eq("owner"),
                eq("Food: I eat oats and milk for breakfast."), eq(7L), eq(2L), anyList());
    }

    /** The snapshot budget cut is explicit: only fitting paragraphs are kept. */
    @Test
    void snapshotSelectionIncludesOnlyParagraphsThatFitTheSnapshotBudget() {
        List<ProfileParagraph> paragraphs = List.of(
                new ProfileParagraph("food", "f".repeat(600), List.of(UUID.randomUUID())),
                new ProfileParagraph("work", "w".repeat(600), List.of(UUID.randomUUID())),
                new ProfileParagraph("schedules", "s".repeat(600), List.of(UUID.randomUUID())));

        ProfileComposer.SnapshotSelection selection = ProfileComposer.select(paragraphs);

        assertThat(selection.text()).contains("Food:", "Work:").doesNotContain("Schedules:");
        assertThat(selection.included()).extracting(ProfileParagraph::category)
                .containsExactly("food", "work");
        assertThat(selection.text().length()).isLessThanOrEqualTo(ProfileComposer.MAX_SNAPSHOT_CHARS);
    }

    /**
     * Snapshot-level source IDs describe the paragraphs actually included in
     * the snapshot text, not every stored topic paragraph.
     */
    @Test
    void snapshotSourceIdsCoverOnlyTheParagraphsIncludedInTheSnapshotText() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryProfileRepository profiles = mock(MemoryProfileRepository.class);
        MemoryService service = new MemoryService(repo, profiles);
        UUID foodId = UUID.randomUUID();
        UUID workId = UUID.randomUUID();
        UUID schedulesId = UUID.randomUUID();
        List<ProfileParagraph> storedParagraphs = List.of(
                new ProfileParagraph("food", "f".repeat(600), List.of(foodId)),
                new ProfileParagraph("work", "w".repeat(600), List.of(workId)),
                new ProfileParagraph("schedules", "s".repeat(600), List.of(schedulesId)));
        // Persisted state: all three paragraphs, with the snapshot text being
        // the budget selection of exactly those rows.
        when(repo.list("owner")).thenReturn(List.of(
                active(foodId, "I eat oats and milk for breakfast"),
                active(workId, "I work as a nurse at the hospital"),
                active(schedulesId, "I go to the gym every weekday morning")));
        when(repo.revision("owner")).thenReturn(4L);
        when(profiles.state("owner")).thenReturn(new ProfileState(2L, 2L, 4L,
                ProfileComposer.select(storedParagraphs).text()));
        when(profiles.paragraphs("owner")).thenReturn(List.of(
                new ProfileParagraphRow("food", 0, "f".repeat(600), 4L, 2L, 1, List.of(foodId)),
                new ProfileParagraphRow("work", 1, "w".repeat(600), 4L, 2L, 1, List.of(workId)),
                new ProfileParagraphRow("schedules", 2, "s".repeat(600), 4L, 2L, 1, List.of(schedulesId))));

        ProfileSnapshot snapshot = service.profile("owner");

        assertThat(snapshot.paragraphs()).extracting(ProfileParagraph::category)
                .containsExactly("food", "work");
        assertThat(snapshot.sourceIds()).containsExactly(foodId, workId);
        assertThat(snapshot.sourceIds()).doesNotContain(schedulesId);
        verify(profiles, never()).replaceParagraphs(anyString(), anyString(), anyLong(), anyLong(), anyList());
    }
}
