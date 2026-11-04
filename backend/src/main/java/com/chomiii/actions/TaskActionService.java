package com.chomiii.actions;

import com.chomiii.chat.ChatApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskActionService {
    private final TaskActionRepository repository;

    public TaskActionService(TaskActionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public TaskActionRepository.Proposal propose(String owner, String title) {
        String clean = title == null ? "" : title.strip();
        if (clean.isEmpty() || clean.length() > 200) {
            throw new ChatApiException(HttpStatus.BAD_REQUEST, "A task title must be 1–200 characters.");
        }
        return repository.propose(owner, clean);
    }

    /**
     * Approval is the sole transition that causes the actual task write.
     * Atomic owner-scoped row locking makes a retry return the same task ID.
     */
    @Transactional
    public TaskActionRepository.Task approve(String owner, UUID proposalId) {
        TaskActionRepository.Proposal proposal = repository.lockProposal(owner, proposalId)
                .orElseThrow(() -> new ChatApiException(HttpStatus.NOT_FOUND, "Task proposal not found."));
        if ("approved".equals(proposal.status())) {
            return repository.taskByProposal(owner, proposalId).orElseThrow(
                    () -> new IllegalStateException("Approved proposal without matching task"));
        }
        if (!"pending".equals(proposal.status())) {
            throw new ChatApiException(HttpStatus.CONFLICT, "This task proposal was rejected.");
        }
        TaskActionRepository.Task task = repository.createTask(owner, proposal);
        if (!repository.markApproved(owner, proposalId)) {
            throw new IllegalStateException("Could not mark an approved task proposal");
        }
        return task;
    }

    @Transactional
    public void reject(String owner, UUID proposalId) {
        TaskActionRepository.Proposal proposal = repository.lockProposal(owner, proposalId)
                .orElseThrow(() -> new ChatApiException(HttpStatus.NOT_FOUND, "Task proposal not found."));
        if (!"pending".equals(proposal.status()) || !repository.reject(owner, proposalId)) {
            throw new ChatApiException(HttpStatus.CONFLICT, "This task proposal is no longer pending.");
        }
    }

    @Transactional(readOnly = true)
    public List<TaskActionRepository.Proposal> pending(String owner) {
        return repository.pending(owner);
    }

    @Transactional(readOnly = true)
    public List<TaskActionRepository.Task> list(String owner) {
        return repository.listTasks(owner);
    }

    @Transactional
    public TaskActionRepository.Task complete(String owner, UUID id) {
        if (!repository.complete(owner, id) && repository.findTask(owner, id).isEmpty()) {
            throw new ChatApiException(HttpStatus.NOT_FOUND, "Task not found.");
        }
        return repository.findTask(owner, id).orElseThrow();
    }
}
