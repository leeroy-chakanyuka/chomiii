package com.chomiii.tools.trace;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access for tool traces. Every read and write is scoped by owner, and
 * conversation reads join the conversations table so a deleted or foreign
 * conversation can never expose rows.
 */
@Repository
class ToolTraceRepository {
    private final JdbcTemplate jdbc;

    ToolTraceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean conversationExists(UUID conversationId, String owner) {
        Integer count = jdbc.queryForObject(
                "select count(*) from conversations where id = ? and owner_hash = ?",
                Integer.class, conversationId.toString(), owner);
        return count != null && count == 1;
    }

    void insert(ToolTraceRow row) {
        jdbc.update("insert into tool_traces (id, owner_hash, conversation_id, request_id, plan_id, step_id, "
                        + "tool_name, capability, effect, provider, model, authorization_decision, approval_id, "
                        + "status, error_code, retry_of_id, idempotency_key, argument_summary, result_summary, "
                        + "requested_at, started_at, finished_at, duration_ms) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.id().toString(), row.ownerHash(), row.conversationId().toString(), row.requestId().toString(),
                uuidOrNull(row.planId()), uuidOrNull(row.stepId()), row.toolName(), row.capability(),
                row.effect(), row.provider(), row.model(), row.authorization(), uuidOrNull(row.approvalId()),
                row.status(), row.errorCode(), uuidOrNull(row.retryOfId()), row.idempotencyKey(),
                ToolTraceSummaries.toJson(row.argumentSummary()), ToolTraceSummaries.toJson(row.resultSummary()),
                Timestamp.from(row.requestedAt()), timestampOrNull(row.startedAt()),
                timestampOrNull(row.finishedAt()), row.durationMs());
    }

    boolean markAuthorized(UUID id, String owner, UUID approvalId) {
        return jdbc.update("update tool_traces set authorization_decision = 'ALLOW', approval_id = ? "
                        + "where id = ? and owner_hash = ? and status = 'requested' and authorization_decision is null",
                uuidOrNull(approvalId), id.toString(), owner) == 1;
    }

    boolean markStarted(UUID id, String owner, Instant startedAt) {
        return jdbc.update("update tool_traces set status = 'running', started_at = ? "
                        + "where id = ? and owner_hash = ? and status = 'requested' and authorization_decision = 'ALLOW'",
                Timestamp.from(startedAt), id.toString(), owner) == 1;
    }

    boolean markTerminal(UUID id, String owner, String status, String errorCode, String resultSummary,
            Instant finishedAt, long durationMs, boolean denied) {
        return jdbc.update("update tool_traces set status = ?, error_code = ?, result_summary = ?, "
                        + "finished_at = ?, duration_ms = ?, "
                        + "authorization_decision = case when ? = 1 then 'DENY' else authorization_decision end "
                        + "where id = ? and owner_hash = ? and ((? = 'succeeded' and status = 'running' "
                        + "and authorization_decision = 'ALLOW' and started_at is not null) "
                        + "or (? <> 'succeeded' and status in ('requested', 'running')))",
                status, errorCode, resultSummary, Timestamp.from(finishedAt), durationMs, denied ? 1 : 0,
                id.toString(), owner, status, status) == 1;
    }

    Optional<ToolTraceRow> find(UUID id, String owner) {
        return jdbc.query("select t.* from tool_traces t join conversations c on c.id = t.conversation_id "
                        + "where t.id = ? and t.owner_hash = ? and c.owner_hash = ?",
                (row, index) -> trace(row), id.toString(), owner, owner).stream().findFirst();
    }

    List<ToolTraceRow> listForConversation(UUID conversationId, String owner) {
        return jdbc.query("select t.* from tool_traces t join conversations c on c.id = t.conversation_id "
                        + "where t.conversation_id = ? and t.owner_hash = ? and c.owner_hash = ? "
                        + "order by t.requested_at, t.id",
                (row, index) -> trace(row), conversationId.toString(), owner, owner);
    }

    private static ToolTraceRow trace(ResultSet row) throws SQLException {
        return new ToolTraceRow(UUID.fromString(row.getString("id")), row.getString("owner_hash"),
                UUID.fromString(row.getString("conversation_id")), UUID.fromString(row.getString("request_id")),
                uuid(row, "plan_id"), uuid(row, "step_id"), row.getString("tool_name"),
                row.getString("capability"), row.getString("effect"), row.getString("provider"),
                row.getString("model"), row.getString("authorization_decision"), uuid(row, "approval_id"),
                row.getString("status"), row.getString("error_code"), uuid(row, "retry_of_id"),
                row.getString("idempotency_key"), ToolTraceSummaries.fromJson(row.getString("argument_summary")),
                ToolTraceSummaries.fromJson(row.getString("result_summary")),
                timestamp(row, "requested_at"), timestamp(row, "started_at"), timestamp(row, "finished_at"),
                row.getObject("duration_ms") == null ? null : row.getLong("duration_ms"));
    }

    private static Object uuidOrNull(UUID value) {
        return value == null ? null : value.toString();
    }

    private static Object timestampOrNull(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static UUID uuid(ResultSet row, String column) throws SQLException {
        String value = row.getString(column);
        return value == null ? null : UUID.fromString(value);
    }

    private static Instant timestamp(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
