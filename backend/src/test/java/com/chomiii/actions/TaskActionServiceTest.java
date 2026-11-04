package com.chomiii.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.chomiii.chat.ChatApiException;

class TaskActionServiceTest {
    @Test
    void proposingCannotCreateTasksBeforeExplicitApproval() {
        TaskActionRepository repo = mock(TaskActionRepository.class);
        TaskActionService service = new TaskActionService(repo);
        var proposed = new TaskActionRepository.Proposal(UUID.randomUUID(),
                "Call the dentist", "pending", Instant.now());
        when(repo.propose("owner", "Call the dentist")).thenReturn(proposed);

        assertThat(service.propose("owner", "  Call the dentist  ")).isEqualTo(proposed);
        verify(repo).propose("owner", "Call the dentist");
        verify(repo, never()).createTask(anyString(), any());
        assertThatThrownBy(() -> service.propose("owner", "  "))
                .isInstanceOf(ChatApiException.class);
        assertThatThrownBy(() -> service.propose("owner", "x".repeat(201)))
                .isInstanceOf(ChatApiException.class);
    }

    @Test
    void approvalWritesExactlyOnceAndRetryReturnsTheSameTask() {
        TaskActionRepository repo = mock(TaskActionRepository.class);
        TaskActionService service = new TaskActionService(repo);
        UUID proposalId = UUID.randomUUID();
        var proposal = new TaskActionRepository.Proposal(proposalId,
                "Call the dentist", "pending", Instant.now());
        var task = new TaskActionRepository.Task(UUID.randomUUID(),
                "Call the dentist", "open", Instant.now());
        when(repo.lockProposal("owner", proposalId)).thenReturn(Optional.of(proposal),
                Optional.of(new TaskActionRepository.Proposal(proposalId,
                        "Call the dentist", "approved", proposal.createdAt())));
        when(repo.createTask("owner", proposal)).thenReturn(task);
        when(repo.markApproved("owner", proposalId)).thenReturn(true);
        when(repo.taskByProposal("owner", proposalId)).thenReturn(Optional.of(task));

        assertThat(service.approve("owner", proposalId)).isEqualTo(task);
        assertThat(service.approve("owner", proposalId).id()).isEqualTo(task.id());
        verify(repo, times(1)).createTask("owner", proposal);
        verify(repo, times(1)).markApproved("owner", proposalId);
    }

    @Test
    void rejectionAndForeignProposalNeverExecuteAnAction() {
        TaskActionRepository repo = mock(TaskActionRepository.class);
        TaskActionService service = new TaskActionService(repo);
        UUID proposalId = UUID.randomUUID();
        assertThatThrownBy(() -> service.approve("different-user", proposalId))
                .isInstanceOf(ChatApiException.class)
                .hasMessageContaining("not found");
        when(repo.lockProposal("owner", proposalId)).thenReturn(Optional.of(
                new TaskActionRepository.Proposal(proposalId, "Create task", "rejected", Instant.now())));
        assertThatThrownBy(() -> service.approve("owner", proposalId))
                .isInstanceOf(ChatApiException.class);
        verify(repo, never()).createTask(anyString(), any());
    }
}
