package com.chomiii.chat;

import com.chomiii.planning.Plan;
import com.chomiii.planning.execution.PlanExecutionService;
import com.chomiii.memory.MemoryLearningService.PriorUserMessage;
import java.time.Duration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final PlanExecutionService plans;

    public ChatTurnStore(JdbcTemplate jdbc, ConversationRepository conversations) {
        this(jdbc, conversations, null);
    }

    @Autowired
    public ChatTurnStore(JdbcTemplate jdbc, ConversationRepository conversations, PlanExecutionService plans) {
        this.jdbc = jdbc;
        this.conversations = conversations;
        this.plans = plans;
    }

    public record Pending(UUID assistantId, List<ContextWindow.Turn> history) {}

    @Transactional
    public Pending begin(UUID conversation, String owner, UUID requestId, String content) {
        return begin(conversation, owner, requestId, content, null);
    }

    @Transactional
    public Pending begin(UUID conversation, String owner, UUID requestId, String content, Long memoryRevision) {
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
        jdbc.update("insert into messages (id, conversation_id, sequence_no, request_id, role, content, status, created_at, memory_revision_at_turn) "
                        + "values (?, ?, ?, ?, 'user', ?, 'complete', ?, ?)",
                UUID.randomUUID().toString(), conversation.toString(), sequence, requestId.toString(), content,
                Timestamp.from(now), memoryRevision);
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

    /** Returns adjacent prior user turns, newest first; an unsafe turn is a barrier. */
    public List<PriorUserMessage> learningHistory(String owner, UUID conversation, UUID requestId, Long revision) {
        if (owner == null || conversation == null || requestId == null || revision == null) return List.of();
        var rows = jdbc.query("select u.content, u.memory_revision_at_turn, u.created_at, a.status as assistant_status "
                        + "from messages current_turn join conversations c on c.id = current_turn.conversation_id "
                        + "join messages u on u.conversation_id = current_turn.conversation_id "
                        + "and u.role = 'user' and u.sequence_no < current_turn.sequence_no "
                        + "left join messages a on a.conversation_id = u.conversation_id "
                        + "and a.request_id = u.request_id and a.role = 'assistant' "
                        + "where c.owner_hash = ? and c.id = ? and current_turn.request_id = ? "
                        + "and current_turn.role = 'user' and current_turn.memory_revision_at_turn = ? "
                        + "order by u.sequence_no desc limit 3",
                (row, index) -> new HistoryRow(row.getString("content"),
                        (Number) row.getObject("memory_revision_at_turn"), row.getTimestamp("created_at"),
                        row.getString("assistant_status")), owner, conversation.toString(), requestId.toString(), revision);
        var result = new java.util.ArrayList<PriorUserMessage>();
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        for (var row : rows) {
            if (row.revision() == null || row.revision().longValue() != revision.longValue()
                    || row.createdAt() == null || row.createdAt().toInstant().isBefore(cutoff)
                    || !"complete".equals(row.assistantStatus())) break;
            result.add(new PriorUserMessage(conversation, revision, row.content()));
        }
        return List.copyOf(result);
    }

    private record HistoryRow(String content, Number revision, Timestamp createdAt, String assistantStatus) {}

    @Transactional
    public void finish(UUID conversation, String owner, UUID requestId, UUID assistant, String content, String status) {
        // Chat start and approval mutations lock the conversation before its messages.
        int released = jdbc.update("update conversations set active_request_id = null, updated_at = ? "
                        + "where id = ? and owner_hash = ? and active_request_id = ?",
                Timestamp.from(Instant.now()), conversation.toString(), owner, requestId.toString());
        if (released != 1) {
            if ("complete".equals(status)) throw new CancellationException("The chat turn is no longer active");
            return;
        }
        int saved = jdbc.update("update messages set content = ?, status = ? "
                        + "where id = ? and conversation_id = ? and request_id = ? and status = 'pending' and role = 'assistant'",
                content, status, assistant.toString(), conversation.toString(), requestId.toString());
        if (saved != 1) throw new IllegalStateException("The assistant message is no longer pending");
    }

    @Transactional
    public void finishWithPlan(UUID conversation, String owner, UUID requestId, UUID assistant, String content, Plan plan) {
        plans.create(owner, conversation, plan);
        finish(conversation, owner, requestId, assistant, content, "complete");
    }

    public com.chomiii.planning.execution.PlanExecution revisionSnapshot(String owner, UUID conversation, UUID plan, long version) {
        return plans.revisionSnapshot(owner, conversation, plan, version);
    }

    @Transactional
    public void finishWithPlan(UUID conversation, String owner, UUID requestId, UUID assistant, String content,
            Plan plan, UUID previousId, long version) {
        plans.revise(owner, conversation, previousId, version, plan);
        finish(conversation, owner, requestId, assistant, content, "complete");
    }

    @Transactional
    public void updatePlanReply(UUID conversation, String owner, UUID requestId, UUID assistant, String content) {
        if (jdbc.query("select id from conversations where id = ? and owner_hash = ? for update",
                (row, index) -> row.getString(1), conversation.toString(), owner).isEmpty()) {
            throw new ConversationNotFoundException();
        }
        int updated = jdbc.update("update messages set content = ? where id = ? and conversation_id = ? and request_id = ? "
                        + "and role = 'assistant' and status = 'complete'",
                content, assistant.toString(), conversation.toString(), requestId.toString());
        if (updated != 1) throw new IllegalStateException("The completed turn is unavailable");
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void interruptOrphanedGenerations() {
        jdbc.update("update conversations set active_request_id = null where active_request_id is not null");
        jdbc.update("update messages set status = 'interrupted' where status = 'pending'");
        jdbc.update("update action_approvals set status = 'CANCELLED' where status = 'PENDING' and plan_id is null "
                + "and exists (select 1 from messages m where m.conversation_id = action_approvals.conversation_id "
                + "and m.request_id = action_approvals.request_id and m.role = 'assistant' and m.status in ('interrupted','failed'))");
        jdbc.update("update tool_traces set status = 'cancelled', error_code = 'REQUEST_CANCELLED', finished_at = ?, duration_ms = 0 "
                + "where status = 'requested' and exists (select 1 from action_approvals a where a.id = tool_traces.approval_id "
                + "and a.owner_hash = tool_traces.owner_hash and a.conversation_id = tool_traces.conversation_id "
                + "and a.status = 'CANCELLED' and a.plan_id is null)", Timestamp.from(Instant.now()));
    }
}
