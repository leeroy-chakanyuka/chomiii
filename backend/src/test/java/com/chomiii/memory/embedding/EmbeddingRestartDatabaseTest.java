package com.chomiii.memory.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.chomiii.memory.MemoryService;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@EnabledIfEnvironmentVariable(named = "CHOMIII_EMBEDDING_RESTART_STAGE", matches = "write|read")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class EmbeddingRestartDatabaseTest {
    private static final String OWNER = "f".repeat(64);
    @Autowired MemoryService memories;
    @Autowired EmbeddingStore store;
    @Autowired EmbeddingProvider provider;
    @Autowired JdbcTemplate jdbc;

    @Test
    void pendingJobAndProfileSurviveDatabaseRestart() {
        if ("write".equals(System.getenv("CHOMIII_EMBEDDING_RESTART_STAGE"))) {
            jdbc.update("insert into browser_sessions (owner_hash, created_at) values (?, ?)", OWNER, Timestamp.from(Instant.now()));
            memories.create(OWNER, "I work as a developer");
            float[] ready = new float[768];
            ready[0] = 1;
            assertThat(store.complete(store.claim(OWNER).orElseThrow(), ready)).isTrue();
            memories.create(OWNER, "I prefer tacos for dinner");
            assertThat(memories.profile(OWNER).text()).contains("tacos", "developer");
        } else {
            try {
                assertThat(memories.profile(OWNER).text()).contains("tacos", "developer");
                var ready = memories.list(OWNER).stream().filter(memory -> memory.content().contains("developer"))
                        .findFirst().orElseThrow();
                assertThat(store.vectors(OWNER, provider.descriptor())).containsKey(ready.id());
                var job = store.claim(OWNER).orElseThrow();
                assertThat(job.content()).contains("tacos");
                float[] vector = new float[768];
                vector[0] = 1;
                assertThat(store.complete(job, vector)).isTrue();
            } finally {
                jdbc.update("delete from browser_sessions where owner_hash = ?", OWNER);
            }
        }
    }
}
