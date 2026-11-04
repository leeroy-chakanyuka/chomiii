package com.chomiii.actions;

import com.chomiii.session.AnonymousSessionFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/actions/tasks")
public class TaskActionController {
    private final TaskActionService service;

    public TaskActionController(TaskActionService service) {
        this.service = service;
    }

    public record Draft(@NotBlank @Size(max = 200) String title) {}

    @PostMapping("/proposals")
    public ResponseEntity<TaskActionRepository.Proposal> propose(
            @RequestAttribute(AnonymousSessionFilter.OWNER_ATTRIBUTE) String owner,
            @Valid @RequestBody Draft request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.propose(owner, request.title()));
    }

    @GetMapping("/proposals")
    public List<TaskActionRepository.Proposal> pending(
            @RequestAttribute(AnonymousSessionFilter.OWNER_ATTRIBUTE) String owner) {
        return service.pending(owner);
    }

    @PostMapping("/proposals/{id}/approve")
    public TaskActionRepository.Task approve(
            @RequestAttribute(AnonymousSessionFilter.OWNER_ATTRIBUTE) String owner,
            @PathVariable UUID id) {
        return service.approve(owner, id);
    }

    @DeleteMapping("/proposals/{id}")
    public ResponseEntity<Void> reject(
            @RequestAttribute(AnonymousSessionFilter.OWNER_ATTRIBUTE) String owner,
            @PathVariable UUID id) {
        service.reject(owner, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public List<TaskActionRepository.Task> list(
            @RequestAttribute(AnonymousSessionFilter.OWNER_ATTRIBUTE) String owner) {
        return service.list(owner);
    }

    @PostMapping("/{id}/complete")
    public TaskActionRepository.Task complete(
            @RequestAttribute(AnonymousSessionFilter.OWNER_ATTRIBUTE) String owner,
            @PathVariable UUID id) {
        return service.complete(owner, id);
    }
}
