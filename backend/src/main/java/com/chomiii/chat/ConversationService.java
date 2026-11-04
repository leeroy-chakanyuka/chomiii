package com.chomiii.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationService {
    private final ConversationRepository repository;

    public ConversationService(ConversationRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ConversationDto.Detail create(String owner) {
        UUID id = UUID.randomUUID();
        repository.create(id, owner, Instant.now());
        return get(id, owner);
    }

    @Transactional(readOnly = true)
    public List<ConversationDto.Summary> list(String owner) {
        return repository.list(owner);
    }

    @Transactional(readOnly = true)
    public ConversationDto.Detail get(UUID id, String owner) {
        var conversation = repository.find(id, owner).orElseThrow(ConversationNotFoundException::new);
        return new ConversationDto.Detail(conversation.id(), conversation.title(), conversation.createdAt(),
                conversation.updatedAt(), repository.messages(id, owner));
    }

    @Transactional
    public void delete(UUID id, String owner) {
        if (!repository.delete(id, owner)) {
            throw new ConversationNotFoundException();
        }
    }
}
