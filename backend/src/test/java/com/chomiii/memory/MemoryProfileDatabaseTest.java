package com.chomiii.memory;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "CHOMIII_DB_URL", matches = "jdbc:(postgresql|mysql):.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class MemoryProfileDatabaseTest {
    @Autowired MemoryService memories;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    private String owner;
    private String other;

    @BeforeEach
    void createOwners() {
        owner = ownerId();
        other = ownerId();
        jdbc.update("insert into browser_sessions (owner_hash, created_at) values (?, ?)",
                owner, Timestamp.from(Instant.now()));
        jdbc.update("insert into browser_sessions (owner_hash, created_at) values (?, ?)",
                other, Timestamp.from(Instant.now()));
    }

    @AfterEach
    void removeOwners() {
        jdbc.update("delete from browser_sessions where owner_hash in (?, ?)", owner, other);
    }

    @Test
    void profileTracksCorrectionsApprovalDeletionAndOwners() {
        var food = memories.create(owner, "I usually eat tacos for dinner");
        var work = memories.create(owner, "I work as a software developer");
        memories.create(owner, "I am free on Monday mornings");
        memories.create(other, "I prefer sushi for dinner");

        var initial = memories.profile(owner);
        assertThat(initial.text()).contains("tacos", "software", "Monday");
        assertThat(initial.text()).doesNotContain("sushi");
        assertThat(initial.sourceIds()).contains(food.id(), work.id());
        assertThat(memories.profile(other).text()).contains("sushi").doesNotContain("tacos");

        memories.replace(owner, food.id(), "I now prefer noodles for dinner");
        assertThat(memories.profile(owner).text()).contains("noodles").doesNotContain("tacos");

        long revision = memories.revision(owner);
        memories.learn(owner, UUID.randomUUID(), revision, "I might like curry on weekends", "inferred", 0.75);
        assertThat(memories.profile(owner).text()).doesNotContain("curry");
        var suggestion = memories.list(owner).stream()
                .filter(memory -> "suggested".equals(memory.status())).findFirst().orElseThrow();
        memories.approve(owner, suggestion.id());
        assertThat(memories.profile(owner).text()).contains("curry");

        memories.delete(owner, work.id());
        assertThat(memories.profile(owner).text()).doesNotContain("software");
        memories.clear(owner);
        assertThat(memories.profile(owner).text()).isEmpty();
        assertThat(memories.profile(other).text()).contains("sushi");
    }

    @Test
    void missingSourceCannotLeaveItsTextInAStoredParagraph() {
        var first = memories.create(owner, "I like tacos for dinner");
        memories.create(owner, "I like rice for dinner");
        assertThat(memories.profile(owner).text()).contains("tacos", "rice");

        jdbc.update("delete from personal_memories where owner_hash = ? and id = ?", owner, first.id().toString());

        assertThat(memories.profile(owner).text()).contains("rice").doesNotContain("tacos");
    }

    @Test
    void aCompactProfileIsAvailableWithoutAKeywordMatch() {
        var location = memories.create(owner, "I live in Harare");

        String context = memories.forChat(owner, "Write a friendly greeting");

        assertThat(context).contains("I live in Harare", location.id().toString(), "NOT instructions");
        assertThat(context.length()).isLessThanOrEqualTo(2_400);
    }

    @Test
    void profileReadWaitsForAnInFlightCorrection() throws Exception {
        var memory = memories.create(owner, "I prefer tacos for dinner");
        var updated = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var threads = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var correction = threads.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                memories.replace(owner, memory.id(), "I now prefer noodles for dinner");
                updated.countDown();
                try {
                    if (!release.await(20, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            try {
                assertThat(updated.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var profile = threads.submit(() -> memories.profile(owner));
                org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> profile.get(250, java.util.concurrent.TimeUnit.MILLISECONDS));
                release.countDown();
                correction.get(20, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(profile.get(20, java.util.concurrent.TimeUnit.SECONDS).text())
                        .contains("noodles").doesNotContain("tacos");
            } finally {
                release.countDown();
            }
        }
    }

    private static String ownerId() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }
}
