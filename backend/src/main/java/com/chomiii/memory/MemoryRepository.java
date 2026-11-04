package com.chomiii.memory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MemoryRepository {
    private final JdbcTemplate jdbc;

    public MemoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Serializes per-session writes so the bounded memory count cannot race. */
    public void lockOwner(String owner) {
        List<String> owners = jdbc.query(
                "select owner_hash from browser_sessions where owner_hash = ? for update",
                (row, index) -> row.getString("owner_hash"), owner);
        if (owners.isEmpty()) throw new IllegalStateException("Unknown memory owner");
    }

    public int count(String owner) {
        Integer count = jdbc.queryForObject(
                "select count(*) from personal_memories where owner_hash = ?", Integer.class, owner);
        return count == null ? 0 : count;
    }

    public List<PersonalMemory> list(String owner) {
        return jdbc.query(
                "select id, content, source, created_at, updated_at from personal_memories "
                        + "where owner_hash = ? order by updated_at desc, id desc limit 100",
                (row, index) -> fromRow(row), owner);
    }

    public PersonalMemory create(String owner, String content) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("insert into personal_memories "
                        + "(id, owner_hash, content, source, created_at, updated_at) "
                        + "values (?, ?, ?, 'user_explicit', ?, ?)",
                id.toString(), owner, content, Timestamp.from(now), Timestamp.from(now));
        return new PersonalMemory(id, content, "user_explicit", now, now);
    }

    public boolean update(String owner, UUID id, String content) {
        return jdbc.update(
                "update personal_memories set content = ?, updated_at = ? "
                        + "where id = ? and owner_hash = ?",
                content, Timestamp.from(Instant.now()), id.toString(), owner) == 1;
    }

    public boolean delete(String owner, UUID id) {
        return jdbc.update("delete from personal_memories where id = ? and owner_hash = ?",
                id.toString(), owner) == 1;
    }

    public int clear(String owner) {
        return jdbc.update("delete from personal_memories where owner_hash = ?", owner);
    }

    private static PersonalMemory fromRow(ResultSet row) throws SQLException {
        return new PersonalMemory(UUID.fromString(row.getString("id")),
                row.getString("content"), row.getString("source"),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant());
    }
}
