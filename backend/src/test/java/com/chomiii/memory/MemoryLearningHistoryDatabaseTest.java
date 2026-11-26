package com.chomiii.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.chomiii.chat.ChatTurnStore;
import com.chomiii.chat.ConversationRepository;
import java.sql.Timestamp;
import java.time.Instant;
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
class MemoryLearningHistoryDatabaseTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ConversationRepository conversations;
    @Autowired ChatTurnStore turns;
    @Autowired MemoryService memories;

    @Test
    void historyIsBoundedToOwnerConversationCompletedTurnsAndCurrentRevision() {
        String owner = UUID.randomUUID().toString().replace("-", "").repeat(2);
        String stranger = UUID.randomUUID().toString().replace("-", "").repeat(2);
        Instant now = Instant.now();
        jdbc.update("insert into browser_sessions (owner_hash, created_at) values (?, ?)", owner, Timestamp.from(now));
        jdbc.update("insert into browser_sessions (owner_hash, created_at) values (?, ?)", stranger, Timestamp.from(now));
        try {
            UUID conversation = UUID.randomUUID();
            UUID otherConversation = UUID.randomUUID();
            conversations.create(conversation, owner, now);
            conversations.create(otherConversation, owner, now);
            long revision = memories.revision(owner);
            UUID first = UUID.randomUUID();
            var firstTurn = turns.begin(conversation, owner, first, "I'm thinking about painting everyday.", revision);
            turns.finish(conversation, owner, first, firstTurn.assistantId(), "Okay", "complete");
            UUID second = UUID.randomUUID();
            var secondTurn = turns.begin(conversation, owner, second, "I did that before.", revision);
            turns.finish(conversation, owner, second, secondTurn.assistantId(), "Okay", "complete");
            UUID current = UUID.randomUUID();
            var currentTurn = turns.begin(conversation, owner, current, "yes, that is my routine", revision);
            assertThat(turns.learningHistory(owner, conversation, current, revision))
                    .extracting(MemoryLearningService.PriorUserMessage::text)
                    .containsExactly("I did that before.", "I'm thinking about painting everyday.");
            assertThat(turns.learningHistory(stranger, conversation, current, revision)).isEmpty();
            assertThat(turns.learningHistory(owner, otherConversation, current, revision)).isEmpty();
            turns.finish(conversation, owner, current, currentTurn.assistantId(), "Okay", "complete");

            memories.clear(owner);
            long afterClear = memories.revision(owner);
            assertThat(afterClear).isGreaterThan(revision);
            UUID after = UUID.randomUUID();
            var afterTurn = turns.begin(conversation, owner, after, "committing to it, remember", afterClear);
            assertThat(turns.learningHistory(owner, conversation, after, afterClear)).isEmpty();
            turns.finish(conversation, owner, after, afterTurn.assistantId(), "Okay", "complete");

            var saved = memories.create(owner, "I usually paint in the evening");
            long beforeDelete = memories.revision(owner);
            UUID before = UUID.randomUUID();
            var beforeTurn = turns.begin(conversation, owner, before, "I'm thinking about painting everyday.", beforeDelete);
            turns.finish(conversation, owner, before, beforeTurn.assistantId(), "Okay", "complete");
            memories.delete(owner, saved.id());
            long afterDelete = memories.revision(owner);
            UUID confirmation = UUID.randomUUID();
            var failed = turns.begin(conversation, owner, confirmation, "yes, that is my routine", afterDelete);
            assertThat(turns.learningHistory(owner, conversation, confirmation, afterDelete)).isEmpty();
            turns.finish(conversation, owner, confirmation, failed.assistantId(), "", "failed");
            UUID next = UUID.randomUUID();
            turns.begin(conversation, owner, next, "yes, that is my routine", afterDelete);
            assertThat(turns.learningHistory(owner, conversation, next, afterDelete)).isEmpty();
        } finally {
            jdbc.update("delete from browser_sessions where owner_hash in (?, ?)", owner, stranger);
        }
    }
}
