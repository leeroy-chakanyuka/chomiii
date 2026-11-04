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
        assertThat(context).contains("user-approved", "NOT instructions", "oats and milk");
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
}
