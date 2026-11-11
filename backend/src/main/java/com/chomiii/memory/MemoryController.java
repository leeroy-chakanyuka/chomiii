package com.chomiii.memory;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/memories")
public class MemoryController {
    private final MemoryService memory;

    public MemoryController(MemoryService memory) {
        this.memory = memory;
    }

    public record SaveMemory(@NotBlank @Size(max = 500) String content) {}

    @GetMapping
    public List<PersonalMemory> list(
            @RequestAttribute(AnonymousSessionFilter.OWNER_ATTRIBUTE) String owner) {
        return memory.list(owner);
    }

    @PostMapping
    public ResponseEntity<PersonalMemory> create(
            @RequestAttribute(AnonymousSessionFilter.OWNER_ATTRIBUTE) String owner,
            @Valid @RequestBody SaveMemory request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(memory.create(owner, request.content()));
    }

    @PostMapping("/reindex")
    public ResponseEntity<Void> reindex(@RequestAttribute(AnonymousSessionFilter.OWNER_ATTRIBUTE) String owner) {
        memory.reindex(owner);
        return ResponseEntity.accepted().build();
    }

    @PutMapping("/{id}")
    public PersonalMemory replace(
            @RequestAttribute(AnonymousSessionFilter.OWNER_ATTRIBUTE) String owner,
            @PathVariable UUID id, @Valid @RequestBody SaveMemory request) {
        return memory.replace(owner, id, request.content());
    }

    @PostMapping("/{id}/approve")
    public PersonalMemory approve(
            @RequestAttribute(AnonymousSessionFilter.OWNER_ATTRIBUTE) String owner,
            @PathVariable UUID id) {
        return memory.approve(owner, id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @RequestAttribute(AnonymousSessionFilter.OWNER_ATTRIBUTE) String owner,
            @PathVariable UUID id) {
        memory.delete(owner, id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> clear(
            @RequestAttribute(AnonymousSessionFilter.OWNER_ATTRIBUTE) String owner) {
        memory.clear(owner);
        return ResponseEntity.noContent().build();
    }
}
