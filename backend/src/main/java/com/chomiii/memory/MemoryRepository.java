package com.chomiii.memory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MemoryRepository {
    private static final String FIELDS =
            "id, content, source, status, confidence, source_conversation_id, claim_topic, superseded_by, created_at, updated_at";
    private final JdbcTemplate jdbc;

    public MemoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Owner row lock serializes lifecycle mutations and auto-learner writes. */
    public void lockOwner(String owner) {
        List<String> owners = jdbc.query(
                "select owner_hash from browser_sessions where owner_hash = ? for update",
                (row, index) -> row.getString("owner_hash"), owner);
        if (owners.isEmpty()) throw new IllegalStateException("Unknown memory owner");
    }

    public long revision(String owner) {
        Long version = jdbc.queryForObject("select memory_revision from browser_sessions where owner_hash = ?",
                Long.class, owner);
        return version == null ? -1L : version;
    }

    public void bumpRevision(String owner) {
        jdbc.update("update browser_sessions set memory_revision = memory_revision + 1 where owner_hash = ?",
                owner);
    }

    public int count(String owner) {
        Integer count = jdbc.queryForObject(
                "select count(*) from personal_memories where owner_hash = ?", Integer.class, owner);
        return count == null ? 0 : count;
    }

    public boolean existsContent(String owner, String content) {
        Integer matches = jdbc.queryForObject(
                "select count(*) from personal_memories where owner_hash = ? and lower(content) = lower(?)",
                Integer.class, owner, content);
        return matches != null && matches > 0;
    }

    public List<PersonalMemory> list(String owner) {
        return jdbc.query("select " + FIELDS + " from personal_memories "
                        + "where owner_hash = ? order by updated_at desc, id desc limit 100",
                (row, index) -> fromRow(row), owner);
    }

    public Optional<PersonalMemory> find(String owner, UUID id) {
        return jdbc.query("select " + FIELDS + " from personal_memories "
                        + "where owner_hash = ? and id = ?",
                (row, index) -> fromRow(row), owner, id.toString()).stream().findFirst();
    }

    public PersonalMemory create(String owner, String content) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("insert into personal_memories "
                        + "(id, owner_hash, content, source, status, confidence, created_at, updated_at) "
                        + "values (?, ?, ?, 'user_explicit', 'active', 1.000, ?, ?)",
                id.toString(), owner, content, Timestamp.from(now), Timestamp.from(now));
        return new PersonalMemory(id, content, "user_explicit", now, now);
    }

    public void createLearned(String owner, UUID conversation, String content,
            String source, String status, double confidence) {
        Instant now = Instant.now();
        jdbc.update("insert into personal_memories "
                        + "(id, owner_hash, content, source, status, confidence, source_conversation_id, "
                        + "created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), owner, content, source, status, confidence,
                conversation.toString(), Timestamp.from(now), Timestamp.from(now));
    }

    public boolean update(String owner, UUID id, String content) {
        return jdbc.update(
                "update personal_memories set content = ?, source = 'user_explicit', "
                        + "status = 'active', confidence = 1.000, claim_topic = NULL, superseded_by = NULL, updated_at = ? "
                        + "where id = ? and owner_hash = ?",
                content, Timestamp.from(Instant.now()), id.toString(), owner) == 1;
    }

    /**
     * Persists the extraction-supplied subject label for a just-inserted
     * learned fact (content is unique per owner at this point). Called only
     * when a label exists, so simpler stores never see the extra statement.
     */
    public void setClaimTopicByContent(String owner, String content, String topic) {
        jdbc.update(
                "update personal_memories set claim_topic = ? "
                        + "where owner_hash = ? and lower(content) = lower(?)",
                topic, owner, content);
    }

    public boolean approve(String owner, UUID id) {
        return jdbc.update("update personal_memories set status = 'active', updated_at = ? "
                        + "where id = ? and owner_hash = ? and status = 'suggested' and superseded_by is null",
                Timestamp.from(Instant.now()), id.toString(), owner) == 1;
    }

    public boolean supersede(String owner, UUID id, UUID replacement) {
        return jdbc.update("update personal_memories set superseded_by = ?, updated_at = ? "
                        + "where id = ? and owner_hash = ? and status = 'active' and superseded_by is null",
                replacement.toString(), Timestamp.from(Instant.now()), id.toString(), owner) == 1;
    }

    public boolean delete(String owner, UUID id) {
        return jdbc.update("delete from personal_memories where id = ? and owner_hash = ?",
                id.toString(), owner) == 1;
    }

    public int clear(String owner) {
        return jdbc.update("delete from personal_memories where owner_hash = ?", owner);
    }

    private static PersonalMemory fromRow(ResultSet row) throws SQLException {
        String conversation = row.getString("source_conversation_id");
        String replacement = row.getString("superseded_by");
        return new PersonalMemory(UUID.fromString(row.getString("id")),
                row.getString("content"), row.getString("source"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(),
                replacement == null ? row.getString("status") : "superseded", row.getDouble("confidence"),
                conversation == null ? null : UUID.fromString(conversation),
                row.getString("claim_topic"), replacement == null ? null : UUID.fromString(replacement));
    }
}
