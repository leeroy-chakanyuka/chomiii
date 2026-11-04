package com.chomiii.chat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatTurnStore {
    private final JdbcTemplate jdbc;
    private final ConversationRepository conversations;

    public ChatTurnStore(JdbcTemplate jdbc, ConversationRepository conversations) {
        this.jdbc = jdbc;
        this.conversations = conversations;
    }

    public record Pending(UUID assistantId, List<ContextWindow.Turn> history) {}

    @Transactional
    public Pending begin(UUID conversation, String owner, UUID requestId, String content) {
        if (conversations.find(conversation, owner).isEmpty()) {
            throw new ConversationNotFoundException();
        }
        Integer duplicates = jdbc.queryForObject(
                "select count(*) from messages where conversation_id = ? and request_id = ?",
                Integer.class, conversation.toString(), requestId.toString());
        if (duplicates != null && duplicates > 0) {
            throw new ChatApiException(HttpStatus.CONFLICT, "This request was already submitted.");
        }
        int claimed = jdbc.update("update conversations set active_request_id = ? "
                        + "where id = ? and owner_hash = ? and active_request_id is null",
                requestId.toString(), conversation.toString(), owner);
        if (claimed != 1) {
            throw new ChatApiException(HttpStatus.CONFLICT, "A response is already in progress for this chat.");
        }
        List<ContextWindow.Turn> history = jdbc.query(
                "select role, content, status from messages where conversation_id = ? order by sequence_no",
                (row, index) -> new ContextWindow.Turn(row.getString("role"), row.getString("content"),
                        row.getString("status")), conversation.toString());
        Long next = jdbc.queryForObject("select coalesce(max(sequence_no), 0) + 1 from messages where conversation_id = ?",
                Long.class, conversation.toString());
        long sequence = next == null ? 1 : next;
        Instant now = Instant.now();
        UUID assistant = UUID.randomUUID();
        jdbc.update("insert into messages (id, conversation_id, sequence_no, request_id, role, content, status, created_at) "
                        + "values (?, ?, ?, ?, 'user', ?, 'complete', ?)",
                UUID.randomUUID().toString(), conversation.toString(), sequence, requestId.toString(), content,
                Timestamp.from(now));
        jdbc.update("insert into messages (id, conversation_id, sequence_no, request_id, role, content, status, created_at) "
                        + "values (?, ?, ?, ?, 'assistant', '', 'pending', ?)",
                assistant.toString(), conversation.toString(), sequence + 1, requestId.toString(), Timestamp.from(now));
        String title = content.strip().replaceAll("\\s+", " ");
        if (title.length() > 80) {
            title = title.substring(0, 80);
        }
        jdbc.update("update conversations set title = case when ? = 1 then ? else title end, updated_at = ? "
                        + "where id = ? and owner_hash = ?",
                sequence, title, Timestamp.from(now), conversation.toString(), owner);
        return new Pending(assistant, history);
    }

    @Transactional
    public void finish(UUID conversation, String owner, UUID requestId, UUID assistant, String content, String status) {
        jdbc.update("update messages set content = ?, status = ? "
                        + "where id = ? and conversation_id = ? and request_id = ? and status = 'pending' "
                        + "and exists (select 1 from conversations c where c.id = ? and c.owner_hash = ?)",
                content, status, assistant.toString(), conversation.toString(), requestId.toString(),
                conversation.toString(), owner);
        jdbc.update("update conversations set active_request_id = null, updated_at = ? "
                        + "where id = ? and owner_hash = ? and active_request_id = ?",
                Timestamp.from(Instant.now()), conversation.toString(), owner, requestId.toString());
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void interruptOrphanedGenerations() {
        jdbc.update("update messages set status = 'interrupted' where status = 'pending'");
        jdbc.update("update conversations set active_request_id = null where active_request_id is not null");
    }
}
