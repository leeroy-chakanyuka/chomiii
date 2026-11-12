package com.chomiii.evaluation;

import com.chomiii.memory.MemoryRepository;
import com.chomiii.memory.PersonalMemory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** A disposable offline store; database modes use the actual JDBC repository. */
final class InMemoryEvaluationRepository extends MemoryRepository {
    private final Map<String, List<PersonalMemory>> entries = new HashMap<>();
    private final Map<String, Long> revisions = new HashMap<>();

    InMemoryEvaluationRepository() { super(null); }

    @Override public synchronized void lockOwner(String owner) { entries.computeIfAbsent(owner, ignored -> new ArrayList<>()); }
    @Override public synchronized long revision(String owner) { return revisions.getOrDefault(owner, 0L); }
    @Override public synchronized void bumpRevision(String owner) { revisions.merge(owner, 1L, Long::sum); }
    @Override public synchronized int count(String owner) { return list(owner).size(); }
    @Override public synchronized boolean existsContent(String owner, String content) {
        return list(owner).stream().anyMatch(memory -> memory.content().equalsIgnoreCase(content));
    }
    @Override public synchronized List<PersonalMemory> list(String owner) {
        return List.copyOf(entries.getOrDefault(owner, List.of()));
    }
    @Override public synchronized Optional<PersonalMemory> find(String owner, UUID id) {
        return list(owner).stream().filter(memory -> memory.id().equals(id)).findFirst();
    }
    @Override public synchronized PersonalMemory create(String owner, String content) {
        Instant now = Instant.now();
        var memory = new PersonalMemory(UUID.randomUUID(), content, "user_explicit", now, now);
        entries.computeIfAbsent(owner, ignored -> new ArrayList<>()).addFirst(memory);
        return memory;
    }
    @Override public synchronized void createLearned(String owner, UUID conversation, String content,
            String source, String status, double confidence) {
        Instant now = Instant.now();
        entries.computeIfAbsent(owner, ignored -> new ArrayList<>()).addFirst(
                new PersonalMemory(UUID.randomUUID(), content, source, now, now, status, confidence, conversation));
    }
    @Override public synchronized boolean update(String owner, UUID id, String content) {
        var current = find(owner, id);
        if (current.isEmpty()) return false;
        var old = current.get();
        entries.get(owner).remove(old);
        entries.get(owner).addFirst(new PersonalMemory(id, content, "user_explicit", old.createdAt(),
                Instant.now(), "active", 1.0, old.sourceConversationId()));
        return true;
    }
    @Override public synchronized boolean approve(String owner, UUID id) {
        var current = find(owner, id);
        if (current.isEmpty() || !"suggested".equals(current.get().status())) return false;
        var old = current.get();
        entries.get(owner).remove(old);
        entries.get(owner).addFirst(new PersonalMemory(id, old.content(), old.source(), old.createdAt(),
                Instant.now(), "active", old.confidence(), old.sourceConversationId()));
        return true;
    }
    @Override public synchronized boolean supersede(String owner, UUID id, UUID replacement) {
        var current = find(owner, id);
        if (current.isEmpty() || !"active".equals(current.get().status())) return false;
        var old = current.get();
        entries.get(owner).remove(old);
        entries.get(owner).addFirst(new PersonalMemory(id, old.content(), old.source(), old.createdAt(),
                Instant.now(), "superseded", old.confidence(), old.sourceConversationId(), old.claimTopic(), replacement));
        return true;
    }
    @Override public synchronized void setClaimTopicByContent(String owner, String content, String topic) {
        var old = list(owner).stream().filter(memory -> memory.content().equalsIgnoreCase(content)).findFirst().orElseThrow();
        entries.get(owner).remove(old);
        entries.get(owner).addFirst(new PersonalMemory(old.id(), old.content(), old.source(), old.createdAt(),
                old.updatedAt(), old.status(), old.confidence(), old.sourceConversationId(), topic, old.supersededBy()));
    }
    @Override public synchronized boolean delete(String owner, UUID id) {
        var memories = entries.get(owner);
        return memories != null && memories.removeIf(memory -> memory.id().equals(id));
    }
    @Override public synchronized int clear(String owner) {
        int size = count(owner);
        entries.computeIfAbsent(owner, ignored -> new ArrayList<>()).clear();
        return size;
    }
}
