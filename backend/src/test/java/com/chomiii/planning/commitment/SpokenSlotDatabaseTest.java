package com.chomiii.planning.commitment;

import static org.assertj.core.api.Assertions.assertThat;

import com.chomiii.chat.ChatTurnStore;
import com.chomiii.chat.ConversationRepository;
import com.chomiii.memory.MemoryService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@EnabledIfEnvironmentVariable(named = "CHOMIII_DB_URL", matches = "jdbc:(postgresql|mysql):.*")
@SpringBootTest
@ActiveProfiles("local")
class SpokenSlotDatabaseTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ConversationRepository conversations;
    @Autowired ChatTurnStore turns;
    @Autowired MemoryService memories;
    @Autowired SpokenSlotService slots;

    @Test
    void remembersOnlyTheUsersSelectionUntilExpiryWithIsolationAndOptOut() {
        String owner = UUID.randomUUID().toString().replace("-", "").repeat(2);
        String other = UUID.randomUUID().toString().replace("-", "").repeat(2);
        Instant now = Instant.now();
        jdbc.update("insert into browser_sessions (owner_hash, created_at) values (?, ?)", owner, Timestamp.from(now));
        jdbc.update("insert into browser_sessions (owner_hash, created_at) values (?, ?)", other, Timestamp.from(now));
        try {
            UUID conversation = UUID.randomUUID();
            UUID otherConversation = UUID.randomUUID();
            conversations.create(conversation, owner, now);
            conversations.create(otherConversation, owner, now);
            long revision = memories.revision(owner);
            UUID discussion = UUID.randomUUID();
            var first = turns.begin(conversation, owner, discussion, "Let's discuss the museum tomorrow", revision);
            turns.finish(conversation, owner, discussion, first.assistantId(), "How about 1:30pm?", "complete");
            UUID selection = UUID.randomUUID();
            var second = turns.begin(conversation, owner, selection, "I'm taking the 1pm slot", revision);
            turns.finish(conversation, owner, selection, second.assistantId(), "Sounds good", "complete");

            assertThat(slots.capture(owner, conversation, selection, revision, "UTC", false)).isEmpty();
            assertThat(slots.current(owner, conversation, revision, false)).isEmpty();
            var choice = slots.capture(owner, conversation, selection, revision, "UTC", true);
            assertThat(choice).isPresent();
            assertThat(choice.orElseThrow().time()).isEqualTo(LocalTime.of(13, 0));
            assertThat(slots.capture(owner, conversation, selection, revision, "UTC", true)).isEqualTo(choice);
            assertThat(jdbc.queryForObject("select count(*) from spoken_slot_choices where owner_hash = ?", Integer.class, owner)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from personal_todos where owner_hash = ?", Integer.class, owner)).isZero();
            assertThat(slots.current(owner, conversation, revision, true)).isEqualTo(choice);
            assertThat(slots.current(owner, conversation, revision, false)).isEmpty();
            assertThat(slots.current(other, conversation, memories.revision(other), true)).isEmpty();
            assertThat(slots.current(owner, otherConversation, revision, true)).isEmpty();
            assertThat(slots.capture(other, conversation, selection, memories.revision(other), "UTC", true)).isEmpty();

            jdbc.update("update spoken_slot_choices set expires_at = ? where owner_hash = ?",
                    Timestamp.from(Instant.now().minusSeconds(1)), owner);
            assertThat(slots.current(owner, conversation, revision, true)).isEmpty();
            // Revision changes prevent old messages being recaptured after the user clears memories.
            memories.clear(owner);
            assertThat(slots.capture(owner, conversation, selection, revision, "UTC", true)).isEmpty();
            assertThat(slots.current(owner, conversation, memories.revision(owner), true)).isEmpty();
        } finally {
            jdbc.update("delete from browser_sessions where owner_hash in (?, ?)", owner, other);
        }
    }
}
