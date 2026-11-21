package com.chomiii.todo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Calendar;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TodoRepository {
    private static final String FIELDS = "id, title, description, status, due_at_micros, timezone, "
            + "source_conversation_id, source_plan_id, created_at, updated_at";
    private final JdbcTemplate jdbc;

    public TodoRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    void lockOwner(String owner) {
        if (jdbc.query("select owner_hash from browser_sessions where owner_hash = ? for update",
                (row, index) -> row.getString(1), owner).isEmpty()) {
            throw new IllegalStateException("Unknown todo owner");
        }
    }

    boolean ownsConversation(String owner, UUID id) {
        return !jdbc.query("select id from conversations where owner_hash = ? and id = ? for update",
                (row, index) -> row.getString(1), owner, id.toString()).isEmpty();
    }

    boolean ownsPlan(String owner, UUID planId, UUID conversationId) {
        if (conversationId == null) {
            return !jdbc.query("select id from plan_executions where owner_hash = ? and id = ? for update",
                    (row, index) -> row.getString(1), owner, planId.toString()).isEmpty();
        }
        return !jdbc.query("select id from plan_executions where owner_hash = ? and id = ? and conversation_id = ? for update",
                (row, index) -> row.getString(1), owner, planId.toString(), conversationId.toString()).isEmpty();
    }

    List<PersonalTodo> list(String owner, TodoService.Status status, int limit) {
        if (status == null) {
            return jdbc.query("select " + FIELDS + " from personal_todos where owner_hash = ? "
                            + "order by created_at, id limit ?", (row, index) -> fromRow(row), owner, limit);
        }
        return jdbc.query("select " + FIELDS + " from personal_todos where owner_hash = ? and status = ? "
                        + "order by created_at, id limit ?", (row, index) -> fromRow(row),
                owner, status == TodoService.Status.OPEN ? "open" : "completed", limit);
    }

    Optional<PersonalTodo> find(String owner, UUID id) {
        return jdbc.query("select " + FIELDS + " from personal_todos where owner_hash = ? and id = ? for update",
                (row, index) -> fromRow(row), owner, id.toString()).stream().findFirst();
    }

    Optional<PersonalTodo> findByKey(String owner, UUID key) {
        return jdbc.query("select " + FIELDS + " from personal_todos where owner_hash = ? and idempotency_key = ? for update",
                (row, index) -> fromRow(row), owner, key.toString()).stream().findFirst();
    }

    Optional<PersonalTodo> findMatchingOpen(String owner, TodoService.CreateTodo request) {
        return jdbc.query("select " + FIELDS + " from personal_todos where owner_hash = ? and status = 'open' and title = ? order by created_at, id",
                (row, index) -> fromRow(row), owner, request.title()).stream()
                .filter(todo -> todo.title().equals(request.title())
                        && java.util.Objects.equals(todo.description(), request.description())
                        && java.util.Objects.equals(todo.dueAt(), request.dueAt())
                        && java.util.Objects.equals(todo.timezone(), request.timezone()))
                .findFirst();
    }

    PersonalTodo create(String owner, UUID key, TodoService.CreateTodo request) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("insert into personal_todos (id, owner_hash, idempotency_key, "
                    + "title, description, status, due_at_micros, timezone, source_conversation_id, source_plan_id, "
                    + "created_at, updated_at) values (?, ?, ?, ?, ?, 'open', ?, ?, ?, ?, ?, ?)");
            statement.setString(1, id.toString());
            statement.setString(2, owner);
            statement.setString(3, key.toString());
            statement.setString(4, request.title());
            statement.setString(5, request.description());
            if (request.dueAt() == null) statement.setNull(6, java.sql.Types.BIGINT);
            else statement.setLong(6, epochMicros(request.dueAt()));
            statement.setString(7, request.timezone());
            statement.setString(8, string(request.sourceConversationId()));
            statement.setString(9, string(request.sourcePlanId()));
            statement.setTimestamp(10, timestamp(now), utc());
            statement.setTimestamp(11, timestamp(now), utc());
            return statement;
        });
        return new PersonalTodo(id, request.title(), request.description(), "open", request.dueAt(),
                request.timezone(), request.sourceConversationId(), request.sourcePlanId(), now, now);
    }

    void complete(String owner, UUID id) {
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("update personal_todos set status = 'completed', updated_at = ? "
                    + "where owner_hash = ? and id = ? and status = 'open'");
            statement.setTimestamp(1, timestamp(Instant.now().truncatedTo(ChronoUnit.MICROS)), utc());
            statement.setString(2, owner);
            statement.setString(3, id.toString());
            return statement;
        });
    }

    private static PersonalTodo fromRow(ResultSet row) throws SQLException {
        return new PersonalTodo(UUID.fromString(row.getString("id")), row.getString("title"),
                row.getString("description"), row.getString("status"), dueAt(row),
                row.getString("timezone"), uuid(row.getString("source_conversation_id")),
                uuid(row.getString("source_plan_id")), instant(row, "created_at"), instant(row, "updated_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column, utc());
        return value == null ? null : value.toInstant();
    }
    private static long epochMicros(Instant value) {
        return Math.addExact(Math.multiplyExact(value.getEpochSecond(), 1_000_000L), value.getNano() / 1_000);
    }
    private static Instant dueAt(ResultSet row) throws SQLException {
        long micros = row.getLong("due_at_micros");
        return row.wasNull() ? null : Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                Math.floorMod(micros, 1_000_000L) * 1_000L);
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Calendar utc() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }
    private static String string(UUID value) { return value == null ? null : value.toString(); }
    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
}
