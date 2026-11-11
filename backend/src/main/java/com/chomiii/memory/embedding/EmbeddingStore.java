package com.chomiii.memory.embedding;

import com.chomiii.memory.MemoryRepository;
import com.chomiii.memory.PersonalMemory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class EmbeddingStore {
    public record Job(String owner, UUID memoryId, String content, String hash, String token,
            EmbeddingProvider.Descriptor descriptor, int attempts) {}
    private record State(String hash, String model, int dimensions, String configuration, String status,
            String token, int attempts) {}

    private final JdbcTemplate jdbc;
    private final MemoryRepository memories;
    private final EmbeddingProvider provider;

    public EmbeddingStore(JdbcTemplate jdbc, MemoryRepository memories, EmbeddingProvider provider) {
        this.jdbc = jdbc;
        this.memories = memories;
        this.provider = provider;
    }

    /** The caller holds the owner lock and the canonical mutation transaction. */
    @Transactional
    public void enqueue(String owner, PersonalMemory memory) {
        if (memory != null && "active".equals(memory.status())) enqueue(owner, memory, false);
    }

    @Transactional
    public void reindex(String owner) {
        memories.lockOwner(owner);
        memories.list(owner).stream().filter(memory -> "active".equals(memory.status()))
                .forEach(memory -> enqueue(owner, memory, true));
    }

    private void enqueue(String owner, PersonalMemory memory, boolean force) {
        var descriptor = provider.descriptor();
        String hash = hash(memory.content());
        State old = state(owner, memory.id());
        if (!force && old != null && hash.equals(old.hash()) && compatible(old, descriptor)) return;
        Instant now = Instant.now();
        jdbc.update("delete from memory_embeddings where owner_hash = ? and memory_id = ?", owner, memory.id().toString());
        if (old == null) {
            jdbc.update("insert into memory_embedding_jobs (owner_hash, memory_id, model_id, dimensions, "
                            + "configuration, content_hash, status, attempts, next_attempt_at, updated_at) "
                            + "values (?, ?, ?, ?, ?, ?, 'pending', 0, ?, ?)",
                    owner, memory.id().toString(), descriptor.model(), descriptor.dimensions(), descriptor.configuration(),
                    hash, Timestamp.from(now), Timestamp.from(now));
        } else {
            jdbc.update("update memory_embedding_jobs set model_id = ?, dimensions = ?, configuration = ?, "
                            + "content_hash = ?, status = 'pending', attempts = 0, next_attempt_at = ?, "
                            + "lease_token = null, lease_until = null, updated_at = ? where owner_hash = ? and memory_id = ?",
                    descriptor.model(), descriptor.dimensions(), descriptor.configuration(), hash,
                    Timestamp.from(now), Timestamp.from(now), owner, memory.id().toString());
        }
    }

    @Transactional
    public Optional<Job> claim(String owner) {
        memories.lockOwner(owner);
        Instant now = Instant.now();
        List<String> ids = jdbc.query("select memory_id from memory_embedding_jobs where owner_hash = ? and "
                        + "((status = 'pending' and next_attempt_at <= ?) or "
                        + "(status = 'running' and lease_until <= ?)) order by updated_at, memory_id limit 1",
                (row, index) -> row.getString(1), owner, Timestamp.from(now), Timestamp.from(now));
        if (ids.isEmpty()) return Optional.empty();
        UUID id = UUID.fromString(ids.getFirst());
        var memory = memories.find(owner, id).filter(item -> "active".equals(item.status()));
        if (memory.isEmpty()) {
            jdbc.update("delete from memory_embedding_jobs where owner_hash = ? and memory_id = ?", owner, id.toString());
            jdbc.update("delete from memory_embeddings where owner_hash = ? and memory_id = ?", owner, id.toString());
            return Optional.empty();
        }
        var descriptor = provider.descriptor();
        State old = state(owner, id);
        if (old.attempts() >= 5) {
            jdbc.update("update memory_embedding_jobs set status = 'failed', lease_token = null, lease_until = null, "
                            + "updated_at = ? where owner_hash = ? and memory_id = ?",
                    Timestamp.from(now), owner, id.toString());
            return Optional.empty();
        }
        String contentHash = hash(memory.get().content());
        if (!compatible(old, descriptor) || old.hash() != null && !old.hash().equals(contentHash)) {
            jdbc.update("delete from memory_embeddings where owner_hash = ? and memory_id = ?", owner, id.toString());
        }
        String token = UUID.randomUUID().toString();
        int attempts = old.attempts() + 1;
        jdbc.update("update memory_embedding_jobs set model_id = ?, dimensions = ?, configuration = ?, "
                        + "content_hash = ?, status = 'running', attempts = ?, lease_token = ?, lease_until = ?, updated_at = ? "
                        + "where owner_hash = ? and memory_id = ?",
                descriptor.model(), descriptor.dimensions(), descriptor.configuration(), contentHash, attempts,
                token, Timestamp.from(now.plusSeconds(60)), Timestamp.from(now), owner, id.toString());
        return Optional.of(new Job(owner, id, memory.get().content(), contentHash, token, descriptor, attempts));
    }

    @Transactional
    public boolean complete(Job job, float[] values) {
        float[] vector = EmbeddingVector.normalized(values, job.descriptor().dimensions());
        memories.lockOwner(job.owner());
        State state = state(job.owner(), job.memoryId());
        if (!matches(state, job)) return false;
        var memory = memories.find(job.owner(), job.memoryId()).filter(item -> "active".equals(item.status()));
        if (memory.isEmpty() || !hash(memory.get().content()).equals(job.hash())) return false;
        jdbc.update("delete from memory_embeddings where owner_hash = ? and memory_id = ?",
                job.owner(), job.memoryId().toString());
        jdbc.update("insert into memory_embeddings (owner_hash, memory_id, model_id, dimensions, configuration, "
                        + "content_hash, vector_text, created_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
                job.owner(), job.memoryId().toString(), job.descriptor().model(), job.descriptor().dimensions(),
                job.descriptor().configuration(), job.hash(), EmbeddingVector.encode(vector), Timestamp.from(Instant.now()));
        jdbc.update("update memory_embedding_jobs set status = 'ready', lease_token = null, lease_until = null, "
                        + "updated_at = ? where owner_hash = ? and memory_id = ?",
                Timestamp.from(Instant.now()), job.owner(), job.memoryId().toString());
        return true;
    }

    @Transactional
    public void retry(Job job) {
        memories.lockOwner(job.owner());
        if (!matches(state(job.owner(), job.memoryId()), job)) return;
        Instant now = Instant.now();
        long delay = Math.min(300, 5L << Math.min(job.attempts(), 5));
        jdbc.update("update memory_embedding_jobs set status = ?, next_attempt_at = ?, lease_token = null, "
                        + "lease_until = null, updated_at = ? where owner_hash = ? and memory_id = ?",
                job.attempts() >= 5 ? "failed" : "pending", Timestamp.from(now.plusSeconds(delay)),
                Timestamp.from(now), job.owner(), job.memoryId().toString());
    }

    @Transactional(readOnly = true)
    public Map<UUID, float[]> vectors(String owner, EmbeddingProvider.Descriptor descriptor) {
        Map<UUID, float[]> result = new HashMap<>();
        jdbc.query("select e.memory_id, e.content_hash, e.vector_text, m.content from memory_embeddings e "
                        + "join personal_memories m on m.owner_hash = e.owner_hash and m.id = e.memory_id "
                        + "where e.owner_hash = ? and m.status = 'active' and e.model_id = ? "
                        + "and e.dimensions = ? and e.configuration = ?", row -> {
                    if (hash(row.getString("content")).equals(row.getString("content_hash"))) {
                        try {
                            result.put(UUID.fromString(row.getString("memory_id")),
                                    EmbeddingVector.decode(row.getString("vector_text"), descriptor.dimensions()));
                        } catch (IllegalArgumentException invalid) {
                            // A corrupt optional vector is ignored; lexical retrieval still works.
                        }
                    }
                }, owner, descriptor.model(), descriptor.dimensions(), descriptor.configuration());
        return Map.copyOf(result);
    }

    private State state(String owner, UUID id) {
        return jdbc.query("select content_hash, model_id, dimensions, configuration, status, lease_token, attempts "
                        + "from memory_embedding_jobs where owner_hash = ? and memory_id = ?",
                (row, index) -> new State(row.getString(1), row.getString(2), row.getInt(3), row.getString(4),
                        row.getString(5), row.getString(6), row.getInt(7)), owner, id.toString())
                .stream().findFirst().orElse(null);
    }

    private static boolean matches(State state, Job job) {
        return state != null && "running".equals(state.status()) && job.token().equals(state.token())
                && job.hash().equals(state.hash()) && compatible(state, job.descriptor());
    }

    private static boolean compatible(State state, EmbeddingProvider.Descriptor descriptor) {
        return state != null && descriptor.model().equals(state.model()) && descriptor.dimensions() == state.dimensions()
                && descriptor.configuration().equals(state.configuration());
    }

    static String hash(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
