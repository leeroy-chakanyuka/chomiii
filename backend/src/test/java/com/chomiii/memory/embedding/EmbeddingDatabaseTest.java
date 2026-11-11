package com.chomiii.memory.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.chomiii.memory.MemoryService;
import com.chomiii.memory.HybridMemorySearch;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@EnabledIfEnvironmentVariable(named = "CHOMIII_DB_URL", matches = "jdbc:(postgresql|mysql):.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class EmbeddingDatabaseTest {
    @Autowired MemoryService memories;
    @Autowired EmbeddingStore store;
    @Autowired EmbeddingProvider provider;
    @Autowired JdbcTemplate jdbc;
    private String owner;
    private String other;

    @BeforeEach
    void owners() {
        owner = id();
        other = id();
        jdbc.update("insert into browser_sessions (owner_hash, created_at) values (?, ?)", owner, Timestamp.from(Instant.now()));
        jdbc.update("insert into browser_sessions (owner_hash, created_at) values (?, ?)", other, Timestamp.from(Instant.now()));
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from browser_sessions where owner_hash in (?, ?)", owner, other);
    }

    @Test
    void correctionInvalidatesOldWorkerAndDeletionRemovesItsVector() {
        var memory = memories.create(owner, "I prefer tacos");
        var old = store.claim(owner).orElseThrow();
        memories.replace(owner, memory.id(), "I now prefer noodles");
        assertThat(store.complete(old, vector())).isFalse();
        var corrected = store.claim(owner).orElseThrow();
        assertThat(corrected.content()).contains("noodles");
        assertThat(store.complete(corrected, vector())).isTrue();
        assertThat(store.vectors(owner, provider.descriptor())).containsOnlyKeys(memory.id());
        assertThat(store.vectors(owner, new EmbeddingProvider.Descriptor("another-model", 768, "search-v1"))).isEmpty();
        assertThat(store.vectors(owner, new EmbeddingProvider.Descriptor(provider.descriptor().model(), 768, "other-config"))).isEmpty();
        assertThat(store.vectors(other, provider.descriptor())).isEmpty();

        memories.delete(owner, memory.id());
        assertThat(store.vectors(owner, provider.descriptor())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from memory_embedding_jobs where owner_hash = ?", Integer.class, owner))
                .isZero();
    }

    @Test
    void expiredWorkCanBeReclaimedAndOldLeaseCannotOverwriteIt() {
        var memory = memories.create(owner, "I work as a developer");
        var first = store.claim(owner).orElseThrow();
        jdbc.update("update memory_embedding_jobs set lease_until = ? where owner_hash = ? and memory_id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), owner, memory.id().toString());

        var recovered = store.claim(owner).orElseThrow();
        assertThat(recovered.token()).isNotEqualTo(first.token());
        assertThat(store.complete(first, vector())).isFalse();
        assertThat(store.complete(recovered, vector())).isTrue();
        memories.reindex(owner);
        assertThat(store.vectors(owner, provider.descriptor())).isEmpty();
        assertThat(store.claim(owner)).isPresent();
    }

    @Test
    void suggestedInferencesAreNotQueuedUntilApproved() {
        memories.learn(owner, UUID.randomUUID(), 0, "I might enjoy early walks", "inferred", 0.75);
        assertThat(store.claim(owner)).isEmpty();
        var suggestion = memories.list(owner).getFirst();
        memories.approve(owner, suggestion.id());
        assertThat(store.claim(owner)).isPresent();
    }

    @Test
    void deletingDuringIndexingCannotRestoreTheRemovedMemory() {
        var memory = memories.create(owner, "I prefer tacos");
        var inFlight = store.claim(owner).orElseThrow();
        memories.delete(owner, memory.id());

        assertThat(store.complete(inFlight, vector())).isFalse();
        assertThat(store.claim(owner)).isEmpty();
        assertThat(store.vectors(owner, provider.descriptor())).isEmpty();
    }

    @Test
    void hybridSearchUsesStoredVectorsWithoutCallingTheProviderForEachMemory() {
        var memory = memories.create(owner, "I enjoy stargazing");
        assertThat(store.complete(store.claim(owner).orElseThrow(), vector())).isTrue();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        EmbeddingProvider fake = new EmbeddingProvider() {
            @Override public Descriptor descriptor() { return provider.descriptor(); }
            @Override public float[] document(String text, String key) { throw new AssertionError("Document re-embedded"); }
            @Override public float[] query(String text, String key) { calls.incrementAndGet(); return vector(); }
        };
        var hybrid = new HybridMemorySearch(memories, store, fake, true);

        assertThat(hybrid.augment(owner, "Write a poem", "key", "").context()).isEmpty();
        assertThat(calls).hasValue(0);
        assertThat(hybrid.augment(owner, "What are my hobbies?", "key", "").context())
                .contains("stargazing", memory.id().toString());
        assertThat(hybrid.augment(owner, "What are my hobbies?", "key", "").context()).contains("stargazing");
        assertThat(calls).hasValue(1);
        assertThat(hybrid.search(other, "hobbies", null, 5, "key")).isEmpty();
    }

    private static float[] vector() {
        float[] vector = new float[768];
        vector[0] = 1;
        return vector;
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }
}
