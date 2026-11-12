package com.chomiii.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chomiii.chat.ChatApiException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class MemoryServiceTest {
    private static PersonalMemory memory(String content) {
        return new PersonalMemory(UUID.randomUUID(), content, "user_explicit", Instant.now(), Instant.now());
    }

    @Test
    void savingIsExplicitAndValidated() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryService service = new MemoryService(repo);
        when(repo.create("owner", "I like green tea")).thenReturn(memory("I like green tea"));
        assertThat(service.create("owner", "  I like green tea  ").content()).isEqualTo("I like green tea");
        verify(repo).lockOwner("owner");
        verify(repo).create("owner", "I like green tea");
        assertThatThrownBy(() -> service.create("owner", "  "))
                .isInstanceOf(ChatApiException.class);
        assertThatThrownBy(() -> service.create("owner", "X".repeat(501)))
                .isInstanceOf(ChatApiException.class);
    }

    @Test
    void boundedMemoryCountCannotSilentlyGrow() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryService service = new MemoryService(repo);
        when(repo.count("owner")).thenReturn(100);
        assertThatThrownBy(() -> service.create("owner", "Another fact"))
                .isInstanceOf(ChatApiException.class)
                .hasMessageContaining("Memory is full");
        verify(repo, never()).create(anyString(), anyString());
    }

    @Test
    void memoryContextNeverClaimsInstructionsOrLiveVerification() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryService service = new MemoryService(repo);
        when(repo.list("owner")).thenReturn(List.of(memory("My usual groceries are oats and milk")));
        String context = service.forChat("owner", "What are my usual groceries?");
        assertThat(context).contains("user-controlled", "NOT instructions", "oats and milk");
        assertThat(context).doesNotContain("system instruction");
        assertThat(service.forChat("owner", "Write a poem about mountains")).isEmpty();
    }

    @Test
    void failureToFetchMemoryDegradesToEmptyWithoutLeakingInternalErrors() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryService service = new MemoryService(repo);
        when(repo.list("owner")).thenThrow(new DataAccessResourceFailureException("database offline"));
        assertThat(service.forChat("owner", "What are my usual groceries?")).isEmpty();
    }

    @Test
    void correctedMemoryIsReadByExactOwnerAndId() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryService service = new MemoryService(repo);
        UUID id = UUID.randomUUID();
        PersonalMemory corrected = new PersonalMemory(id, "New favorite groceries", "user_explicit",
                Instant.now(), Instant.now());
        when(repo.update("owner", id, "New favorite groceries")).thenReturn(true);
        when(repo.find("owner", id)).thenReturn(java.util.Optional.of(corrected));
        assertThat(service.replace("owner", id, "  New favorite groceries ")).isEqualTo(corrected);
        verify(repo).find("owner", id);
    }

    @Test
    void deletingARecordUsesOwnerAndExactId() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryService service = new MemoryService(repo);
        UUID id = UUID.randomUUID();
        when(repo.delete("owner", id)).thenReturn(true);
        service.delete("owner", id);
        verify(repo).delete("owner", id);
        assertThatThrownBy(() -> service.delete("different-user", id))
                .isInstanceOf(ChatApiException.class);
    }
    @Test
    void conservativeExtractionKeepsInferencesAsInactiveSuggestions() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryService service = new MemoryService(repo);
        UUID conversation = UUID.randomUUID();
        when(repo.revision("owner")).thenReturn(7L);
        service.learn("owner", conversation, 7L, "I might like early afternoon walks",
                "inferred", 0.76);
        verify(repo).createLearned("owner", conversation, "I might like early afternoon walks",
                "inferred", "suggested", 0.76);
        service.learn("owner", conversation, 7L, "I prefer weekday early walks",
                "conversation_fact", 0.96);
        verify(repo).createLearned("owner", conversation, "I prefer weekday early walks",
                "conversation_fact", "active", 0.96);
        service.learn("owner", conversation, 7L, "Something invented", "unknown", 1.0);
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.times(2))
                .createLearned(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(UUID.class),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    void manualDeletionInvalidatesQueuedLearningRevision() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryService service = new MemoryService(repo);
        UUID conversation = UUID.randomUUID();
        when(repo.revision("owner")).thenReturn(9L);
        service.learn("owner", conversation, 8L, "I prefer Golden Arrow buses",
                "conversation_fact", 0.97);
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never())
                .createLearned(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(UUID.class),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyDouble());
        service.clear("owner");
        verify(repo).bumpRevision("owner");
    }

    @Test
    void pendingInferencesAreNotUsedAsFactsUntilApproved() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryService service = new MemoryService(repo);
        PersonalMemory tentative = new PersonalMemory(UUID.randomUUID(),
                "I might prefer Saturdays", "inferred", Instant.now(), Instant.now(),
                "suggested", 0.75, null);
        when(repo.list("owner")).thenReturn(List.of(tentative));
        assertThat(service.forChat("owner", "Which day do I prefer?")).isEmpty();
        when(repo.approve("owner", tentative.id())).thenReturn(true);
        when(repo.find("owner", tentative.id())).thenReturn(java.util.Optional.of(tentative));
        service.approve("owner", tentative.id());
        verify(repo).approve("owner", tentative.id());
    }

    @Test
    void planningUsesOnlySelectedActiveMemoryIdsForTheOwner() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryService service = new MemoryService(repo);
        PersonalMemory active = memory("I prefer appointments after ten");
        PersonalMemory suggested = new PersonalMemory(UUID.randomUUID(), active.content(), "inferred",
                Instant.now(), Instant.now(), "suggested", 0.75, null);
        PersonalMemory unrelated = memory("I like science fiction films");
        when(repo.list("owner")).thenReturn(List.of(suggested, active, unrelated));
        assertThat(service.forPlanning("owner", "Plan my appointments"))
                .extracting(PersonalMemory::id).containsExactly(active.id());
        assertThat(service.forPlanning("other-owner", "Plan my appointments")).isEmpty();
    }

    @Test
    void definiteConflictsStaySuggestedUntilTheActiveFactIsResolved() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryService service = new MemoryService(repo);
        UUID conversation = UUID.randomUUID();
        when(repo.revision("owner")).thenReturn(7L);
        when(repo.list("owner")).thenReturn(List.of(memory("I work at the Riverside library")));
        service.learn("owner", conversation, 7L,
                "I changed jobs and now work at the Cedar bakery", "conversation_fact", 0.95);
        verify(repo).createLearned("owner", conversation,
                "I changed jobs and now work at the Cedar bakery", "conversation_fact", "suggested", 0.95);
    }

    @Test
    void unresolvedChangeStatementsStaySuggestedInsteadOfAutoActivating() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryService service = new MemoryService(repo);
        UUID conversation = UUID.randomUUID();
        when(repo.revision("owner")).thenReturn(7L);
        when(repo.list("owner")).thenReturn(List.of(memory("I mainly write Python")));
        service.learn("owner", conversation, 7L, "I now use Rust", "conversation_fact", 0.95);
        verify(repo).createLearned("owner", conversation, "I now use Rust",
                "conversation_fact", "suggested", 0.95);
    }

    @Test
    void uncertainStatementsStayReviewableEvenWithoutExistingFacts() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryService service = new MemoryService(repo);
        UUID conversation = UUID.randomUUID();
        when(repo.revision("owner")).thenReturn(7L);
        service.learn("owner", conversation, 7L, "I might change careers next year",
                "conversation_fact", 0.95);
        verify(repo).createLearned("owner", conversation, "I might change careers next year",
                "conversation_fact", "suggested", 0.95);
    }

    @Test
    void approvingAConflictingCorrectionIsBlockedUntilTheOldFactIsResolved() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryService service = new MemoryService(repo);
        UUID id = UUID.randomUUID();
        PersonalMemory proposed = new PersonalMemory(id, "I now work at the Cedar bakery",
                "conversation_fact", Instant.now(), Instant.now(), "suggested", 0.95, null);
        when(repo.find("owner", id)).thenReturn(java.util.Optional.of(proposed));
        when(repo.list("owner")).thenReturn(List.of(proposed, memory("I work at the Riverside library")));
        assertThatThrownBy(() -> service.approve("owner", id))
                .isInstanceOf(ChatApiException.class)
                .hasMessageContaining("conflicts with an active memory");
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never())
                .approve(anyString(), org.mockito.ArgumentMatchers.any(UUID.class));
    }

    @Test
    void extractionTopicIdentityMakesAWordlessCorrectionDefinite() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryService service = new MemoryService(repo);
        UUID id = UUID.randomUUID();
        PersonalMemory proposed = new PersonalMemory(id, "I moved to Bulawayo", "conversation_fact",
                Instant.now(), Instant.now(), "suggested", 0.95, null, "residence");
        PersonalMemory residence = new PersonalMemory(UUID.randomUUID(), "I live in Harare",
                "conversation_fact", Instant.now(), Instant.now(), "active", 0.95, null, "residence");
        when(repo.find("owner", id)).thenReturn(java.util.Optional.of(proposed));
        when(repo.list("owner")).thenReturn(List.of(proposed, residence));
        assertThatThrownBy(() -> service.approve("owner", id))
                .isInstanceOf(ChatApiException.class)
                .hasMessageContaining("conflicts with an active memory");
    }

    @Test
    void approvalOfAnAmbiguousChangeRemainsAvailableToTheReviewer() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryService service = new MemoryService(repo);
        UUID id = UUID.randomUUID();
        PersonalMemory proposed = new PersonalMemory(id, "I moved to Bulawayo", "conversation_fact",
                Instant.now(), Instant.now(), "suggested", 0.95, null);
        when(repo.find("owner", id)).thenReturn(java.util.Optional.of(proposed));
        when(repo.list("owner")).thenReturn(List.of(proposed, memory("I live in Harare")));
        when(repo.approve("owner", id)).thenReturn(true);
        service.approve("owner", id);
        verify(repo).approve("owner", id);
    }

    @Test
    void learnedSlotTopicsArePersistedWithTheFact() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryService service = new MemoryService(repo);
        UUID conversation = UUID.randomUUID();
        when(repo.revision("owner")).thenReturn(7L);
        service.learn("owner", conversation, 7L, "I moved to Bulawayo",
                "conversation_fact", 0.96, "residence");
        verify(repo).createLearned("owner", conversation, "I moved to Bulawayo",
                "conversation_fact", "active", 0.96);
        verify(repo).setClaimTopicByContent("owner", "I moved to Bulawayo", "residence");
    }

}
