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
import com.chomiii.tools.ToolExecutionContext;
import com.chomiii.tools.ToolRegistry;
import com.chomiii.tools.ToolTurn;

@EnabledIfEnvironmentVariable(named = "CHOMIII_DB_URL", matches = "jdbc:(postgresql|mysql):.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class MemoryProfileDatabaseTest {
    @Autowired MemoryService memories;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ToolRegistry tools;
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
        assertThat(memories.forChat(owner, "What should I eat for dinner?"))
                .contains("noodles").doesNotContain("tacos");

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
    void changedCommuteStaysSuggestedUntilOldFactIsResolved() {
        var old = memories.create(owner, "I commute by bus");
        long revision = memories.revision(owner);

        memories.learn(owner, UUID.randomUUID(), revision,
                "I now commute by bicycle", "conversation_fact", 0.96);

        var candidate = memories.list(owner).stream()
                .filter(item -> item.content().equals("I now commute by bicycle"))
                .findFirst().orElseThrow();
        assertThat(candidate.status()).isEqualTo("suggested");
        assertThat(memories.profile(owner).text()).contains("bus").doesNotContain("bicycle");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> memories.approve(owner, candidate.id()))
                .isInstanceOf(com.chomiii.chat.ChatApiException.class)
                .hasMessageContaining("conflicts with an active memory");

        memories.delete(owner, old.id());
        memories.approve(owner, candidate.id());
        assertThat(memories.profile(owner).text()).contains("bicycle").doesNotContain("bus");
    }

    @Test
    void explicitReplacementKeepsHistoryButExcludesItFromCurrentContextAndVectors() {
        var old = memories.create(owner, "I commute by bus");
        memories.learn(owner, UUID.randomUUID(), memories.revision(owner),
                "I now commute by bicycle", "conversation_fact", 0.96);
        var candidate = memories.list(owner).stream()
                .filter(item -> item.content().equals("I now commute by bicycle"))
                .findFirst().orElseThrow();
        long staleRevision = memories.revision(owner);

        memories.approveReplacing(owner, candidate.id(), old.id());

        var historical = memories.list(owner).stream().filter(item -> item.id().equals(old.id())).findFirst().orElseThrow();
        assertThat(historical.status()).isEqualTo("superseded");
        assertThat(historical.supersededBy()).isEqualTo(candidate.id());
        assertThat(memories.profile(owner).text()).contains("bicycle").doesNotContain("bus");
        assertThat(memories.search(owner, "commute", null, 10)).extracting(PersonalMemory::id)
                .contains(candidate.id()).doesNotContain(old.id());
        assertThat(jdbc.queryForObject("select count(*) from memory_embedding_jobs where owner_hash = ? and memory_id = ?",
                Integer.class, owner, old.id().toString())).isZero();

        memories.delete(owner, candidate.id());
        assertThat(memories.list(owner)).anySatisfy(item -> {
            assertThat(item.id()).isEqualTo(old.id());
            assertThat(item.status()).isEqualTo("superseded");
        });
        assertThat(memories.forChat(owner, "How do I commute?")).doesNotContain("bus");
        memories.delete(owner, old.id());
        memories.learn(owner, UUID.randomUUID(), staleRevision, "I commute by bus", "conversation_fact", 0.96);
        assertThat(memories.list(owner)).isEmpty();
    }

    @Test
    void replacementCannotUseAnotherOwnersMemory() {
        var foreign = memories.create(other, "I commute by train");
        memories.learn(owner, UUID.randomUUID(), memories.revision(owner),
                "I now commute by bicycle", "inferred", 0.8);
        var candidate = memories.list(owner).getFirst();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> memories.approveReplacing(owner, candidate.id(), foreign.id()))
                .isInstanceOf(com.chomiii.chat.ChatApiException.class);
        assertThat(memories.list(other)).extracting(PersonalMemory::status).containsExactly("active");
        assertThat(memories.list(owner)).extracting(PersonalMemory::status).containsExactly("suggested");
    }

    @Test
    void differentPersonalTopicsDoNotTriggerAConflict() {
        memories.create(owner, "I commute by bus");
        long revision = memories.revision(owner);
        memories.learn(owner, UUID.randomUUID(), revision,
                "I live in Harare", "conversation_fact", 0.96);
        var learned = memories.list(owner).stream()
                .filter(item -> item.content().equals("I live in Harare"))
                .findFirst().orElseThrow();
        assertThat(learned.status()).isEqualTo("active");
    }

    @Test
    void differentHomesDoNotAutomaticallyCountAsAConflictWithoutAChangeStatement() {
        memories.create(owner, "I live in Goodwood");
        long revision = memories.revision(owner);
        memories.learn(owner, UUID.randomUUID(), revision,
                "I live in Franschhoek", "conversation_fact", 0.96);
        assertThat(memories.list(owner)).filteredOn(item -> "active".equals(item.status()))
                .hasSize(2);
        assertThat(memories.profile(owner).text()).contains("Goodwood", "Franschhoek");
    }

    @Test
    void paraphrasedSameSlotFactDoesNotCreateASecondActiveMemory() {
        memories.create(owner, "I commute by bus");
        long revision = memories.revision(owner);
        memories.learn(owner, UUID.randomUUID(), revision,
                "I currently travel by bus", "conversation_fact", 0.96);
        assertThat(memories.list(owner)).hasSize(1);
        assertThat(memories.profile(owner).text()).contains("commute by bus");
    }

    @Test
    void coexistingCommutesStayActiveAndExplicitCorrectionsRemainReviewable() {
        var work = memories.create(owner, "I commute to work by train");
        var dinner = memories.create(owner, "I prefer tacos for dinner");
        var conversation = UUID.randomUUID();
        long revision = memories.revision(owner);

        memories.learn(owner, conversation, revision, "I now commute to school by bus", "conversation_fact", 0.96);
        memories.learn(owner, conversation, revision, "I now commute to work by bus", "conversation_fact", 0.96);
        memories.learn(owner, conversation, revision, "I now prefer noodles for dinner", "conversation_fact", 0.96);

        var records = memories.list(owner);
        assertThat(records).anySatisfy(memory -> {
            assertThat(memory.content()).isEqualTo("I now commute to school by bus");
            assertThat(memory.status()).isEqualTo("active");
            assertThat(memory.sourceConversationId()).isEqualTo(conversation);
        });
        assertThat(records).filteredOn(memory -> memory.content().equals("I now commute to work by bus")
                        || memory.content().equals("I now prefer noodles for dinner"))
                .hasSize(2).allSatisfy(memory -> {
                    assertThat(memory.status()).isEqualTo("suggested");
                    assertThat(memory.source()).isEqualTo("conversation_fact");
                    assertThat(memory.sourceConversationId()).isEqualTo(conversation);
                });
        assertThat(records).extracting(PersonalMemory::id).contains(work.id(), dinner.id());
        assertThat(memories.profile(owner).text()).contains("train", "tacos").doesNotContain("noodles");
    }

    @Test
    void aCompactProfileIsAvailableWithoutAKeywordMatch() {
        var location = memories.create(owner, "I live in Harare");

        String context = memories.forChat(owner, "Write a friendly greeting");

        assertThat(context).contains("I live in Harare", location.id().toString(), "NOT instructions");
        assertThat(context.length()).isLessThanOrEqualTo(2_400);
    }

    @Test
    void registeredMemorySearchUsesOnlyTheTrustedOwnerAndRespectsRecallOptOut() {
        var mine = memories.create(owner, "I prefer tacos for dinner");
        memories.create(other, "I prefer noodles for dinner");
        var context = new ToolExecutionContext(owner, UUID.randomUUID(), UUID.randomUUID(), "fake-key", true, ignored -> {});
        var turn = new ToolTurn(tools, context, false);

        assertThat(turn.definitions()).extracting(definition -> definition.name()).contains("memory.search");
        assertThat(turn.invoke("memory.search", java.util.Map.of("query", "dinner")).toString())
                .contains("tacos", mine.id().toString()).doesNotContain("noodles");

        var disabled = new ToolTurn(tools,
                new ToolExecutionContext(owner, UUID.randomUUID(), UUID.randomUUID(), "fake-key", false, ignored -> {}), false);
        assertThat(disabled.definitions()).extracting(definition -> definition.name()).doesNotContain("memory.search");
        assertThat(disabled.invoke("memory.search", java.util.Map.of("query", "dinner"))).containsKey("error");
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
