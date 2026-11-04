package com.chomiii.chat;

import com.chomiii.session.AnonymousSessionFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/conversations/{id}/messages")
public class ChatStreamController {
    private final ChatStreamService chat;

    public ChatStreamController(ChatStreamService chat) {
        this.chat = chat;
    }

    public record MessageRequest(@NotNull UUID requestId, @NotBlank String provider,
            @NotBlank String model, @NotBlank @Size(max = 8000) String content) {}

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter send(@PathVariable UUID id,
            @RequestAttribute(AnonymousSessionFilter.OWNER_ATTRIBUTE) String owner,
            @RequestHeader("X-Gemini-Api-Key") String key,
            @Valid @RequestBody MessageRequest request) {
        return chat.start(id, owner, request, key);
    }

    @PostMapping("/{requestId}/cancel")
    public ResponseEntity<Map<String, String>> cancel(@PathVariable UUID id, @PathVariable UUID requestId,
            @RequestAttribute(AnonymousSessionFilter.OWNER_ATTRIBUTE) String owner) {
        chat.cancel(id, owner, requestId);
        return ResponseEntity.accepted().body(Map.of("status", "stopping"));
    }
}
