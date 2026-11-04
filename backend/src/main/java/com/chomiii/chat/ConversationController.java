package com.chomiii.chat;

import com.chomiii.session.AnonymousSessionFilter;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {
    private final ConversationService service;
    private final ChatStreamService chat;

    public ConversationController(ConversationService service, ChatStreamService chat) {
        this.service = service;
        this.chat = chat;
    }

    @PostMapping
    public ResponseEntity<ConversationDto.Detail> create(
            @RequestAttribute(AnonymousSessionFilter.OWNER_ATTRIBUTE) String owner) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(owner));
    }

    @GetMapping
    public List<ConversationDto.Summary> list(
            @RequestAttribute(AnonymousSessionFilter.OWNER_ATTRIBUTE) String owner) {
        return service.list(owner);
    }

    @GetMapping("/{id}")
    public ConversationDto.Detail get(@PathVariable UUID id,
            @RequestAttribute(AnonymousSessionFilter.OWNER_ATTRIBUTE) String owner) {
        return service.get(id, owner);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
            @RequestAttribute(AnonymousSessionFilter.OWNER_ATTRIBUTE) String owner) {
        chat.cancelConversation(id, owner);
        service.delete(id, owner);
        return ResponseEntity.noContent().build();
    }
}
