package com.chomiii.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.chomiii.chat.ConversationRepository;
import com.chomiii.memory.MemoryService;
import com.chomiii.memory.PersonalMemory;
import com.chomiii.tools.ToolDefinition;
import com.chomiii.tools.ToolExecutionContext;
import com.chomiii.tools.ToolRegistry;
import com.chomiii.tools.ToolTurn;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@EnabledIfEnvironmentVariable(named = "EVAL_DISPOSABLE_DB", matches = "true")
@EnabledIfSystemProperty(named = "eval.mode", matches = "integration|live")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class PersonaSeedDatabaseTest {
    @Autowired MemoryService memories;
    @Autowired JdbcTemplate jdbc;
    @Autowired ConversationRepository conversations;
    @Autowired ToolRegistry registry;
    private String owner;
    private String other;
    private UUID conversation;

    @BeforeEach
    void createIsolatedOwners() {
        DisposableEvaluationDatabase.validate(System.getenv());
        owner = ownerId();
        other = ownerId();
        Instant now = Instant.now();
        jdbc.update("insert into browser_sessions (owner_hash, created_at) values (?, ?)", owner, Timestamp.from(now));
        jdbc.update("insert into browser_sessions (owner_hash, created_at) values (?, ?)", other, Timestamp.from(now));
        conversation = UUID.randomUUID();
        conversations.create(conversation, owner, now);
    }

    @AfterEach
    void removeOwners() {
        jdbc.update("delete from browser_sessions where owner_hash in (?, ?)", owner, other);
    }

    @Test
    void seededPersonaSupportsRecallPlanningToolsAndOwnerIsolation() {
        var seeded = PersonaSeed.durable(memories, owner);
        String context = memories.forChat(owner, "Where do I live and what do I cook?");
        assertThat(context).contains("Northgate", "vegetarian", "NOT instructions");
        assertThat(memories.profile(owner).text()).contains("dental", "Northgate", "Colton", "recipe");

        var oracleIds = seeded.stream()
                .filter(item -> List.of("appointments-car", "projects-deadline").contains(item.factId()))
                .map(PersonaSeed.Seeded::memoryId).toList();
        assertThat(memories.forPlanning(owner, FictionalPersona.planningQuestion()))
                .extracting(PersonalMemory::id).containsAll(oracleIds);

        var mine = new ToolTurn(registry, new ToolExecutionContext(owner, conversation,
                UUID.randomUUID(), null, true, ignored -> {}), false);
        assertThat(mine.definitions()).extracting(ToolDefinition::name).contains("memory.search");
        assertThat(mine.invoke("memory.search", Map.of("query", "appointment")).toString())
                .contains("car service appointment");
        mine.close();

        var foreign = new ToolTurn(registry, new ToolExecutionContext(other, UUID.randomUUID(),
                UUID.randomUUID(), null, true, ignored -> {}), false);
        assertThat(foreign.invoke("memory.search", Map.of("query", "appointment")).toString())
                .doesNotContain("car service", "Northgate");
        assertThat(memories.list(other)).isEmpty();
        foreign.close();
    }

    @Test
    void learningAndExplicitCorrectionsUpdateTheRealProfile() {
        var old = PersonaSeed.fact(memories, owner, FictionalPersona.corrections().getFirst().outdatedFact());
        var correction = FictionalPersona.corrections().getFirst();
        memories.learn(owner, conversation, memories.revision(owner), correction.corrected(), "conversation_fact", 0.95);
        var suggestion = memories.list(owner).stream()
                .filter(memory -> memory.content().equals(correction.corrected())).findFirst().orElseThrow();
        assertThat(suggestion.status()).isEqualTo("suggested");
        memories.approveReplacing(owner, suggestion.id(), old.memoryId());
        assertThat(memories.profile(owner).text()).contains(correction.corrected()).doesNotContain(correction.outdated());
        assertThat(memories.list(owner)).anyMatch(memory -> memory.id().equals(old.memoryId())
                && "superseded".equals(memory.status()));

        var ambiguous = PersonaSeed.ambiguous(memories, owner, conversation);
        assertThat(memories.list(owner)).filteredOn(memory -> ambiguous.stream()
                .anyMatch(item -> item.memoryId().equals(memory.id())))
                .allSatisfy(memory -> assertThat(memory.status()).isEqualTo("suggested"));
    }

    private static String ownerId() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }
}
