package com.chomiii.memory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistence for derived profile rows: one profile state row per owner
 * (carrying the profile content revision), category paragraphs stamped with
 * the revisions they were built from, and paragraph-to-canonical-memory
 * source relationships. Every write happens inside the caller's transaction
 * while the caller holds the owner row lock, so derived rows stay consistent
 * with the canonical memory rows they cite.
 */
@Repository
public class MemoryProfileRepository {
    private final JdbcTemplate jdbc;

    public MemoryProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Revisions the derived profile was built against. A missing row reads as
     * {@code builtContentRevision = basedOnRevision = -1} ("never built") so a
     * pre-migration owner is detected as stale on first use.
     */
    public record ProfileState(long contentRevision, long builtContentRevision,
            long basedOnRevision, String snapshotText) {}

    /** A stored paragraph plus the revisions it is strictly linked to. */
    public record ProfileParagraphRow(String category, int position, String text,
            long ownerMemoryRevision, long contentRevision, int sourceCount, List<UUID> sourceIds) {
        public ProfileParagraphRow {
            sourceIds = List.copyOf(sourceIds);
        }
    }

    public ProfileState state(String owner) {
        List<ProfileState> states = jdbc.query(
                "select content_revision, built_content_revision, based_on_revision, snapshot_text "
                        + "from memory_profiles where owner_hash = ?",
                (row, index) -> new ProfileState(
                        row.getLong("content_revision"),
                        revision(row, "built_content_revision"),
                        revision(row, "based_on_revision"),
                        row.getString("snapshot_text")),
                owner);
        return states.isEmpty() ? null : states.get(0);
    }

    public long contentRevision(String owner) {
        Long value = jdbc.queryForObject(
                "select content_revision from memory_profiles where owner_hash = ?",
                Long.class, owner);
        return value == null ? 0L : value;
    }

    public List<ProfileParagraphRow> paragraphs(String owner) {
        List<StoredParagraph> stored = jdbc.query(
                "select p.category, p.position, p.text, p.owner_memory_revision, "
                        + "p.content_revision, p.source_count, s.memory_id "
                        + "from memory_profile_paragraphs p "
                        + "left join memory_profile_sources s on s.paragraph_id = p.id "
                        + "where p.owner_hash = ? order by p.position, s.memory_id",
                (row, index) -> new StoredParagraph(row.getString("category"),
                        row.getInt("position"), row.getString("text"),
                        row.getLong("owner_memory_revision"), row.getLong("content_revision"),
                        row.getInt("source_count"), row.getString("memory_id")),
                owner);
        Map<String, ProfileParagraphRow> paragraphs = new LinkedHashMap<>();
        for (StoredParagraph paragraph : stored) {
            ProfileParagraphRow current = paragraphs.get(paragraph.category());
            List<UUID> sourceIds = current == null
                    ? new ArrayList<>() : new ArrayList<>(current.sourceIds());
            if (paragraph.memoryId() != null) sourceIds.add(UUID.fromString(paragraph.memoryId()));
            paragraphs.put(paragraph.category(), new ProfileParagraphRow(paragraph.category(),
                    paragraph.position(),
                    current == null ? paragraph.text() : current.text(),
                    paragraph.ownerMemoryRevision(), paragraph.contentRevision(),
                    paragraph.sourceCount(), sourceIds));
        }
        return List.copyOf(paragraphs.values());
    }

    /** Idempotent; callers hold the owner lock, so the check-and-insert is atomic. */
    public void ensureRow(String owner) {
        Integer existing = jdbc.queryForObject(
                "select count(*) from memory_profiles where owner_hash = ?", Integer.class, owner);
        if (existing != null && existing > 0) return;
        jdbc.update("insert into memory_profiles "
                        + "(owner_hash, content_revision, built_content_revision, based_on_revision, "
                        + "snapshot_text, updated_at) values (?, 0, -1, -1, null, ?)",
                owner, Timestamp.from(Instant.now()));
    }

    /** Advances the profile content revision for one successful learned insertion. */
    public void bumpContentRevision(String owner) {
        jdbc.update("update memory_profiles "
                        + "set content_revision = content_revision + 1, updated_at = ? where owner_hash = ?",
                Timestamp.from(Instant.now()), owner);
    }

    /** Replaces all derived rows for the owner, stamped with the given revisions. */
    public void replaceParagraphs(String owner, String snapshotText, long ownerMemoryRevision,
            long contentRevision, List<ProfileParagraph> paragraphs) {
        jdbc.update("delete from memory_profile_sources where paragraph_id in "
                + "(select id from memory_profile_paragraphs where owner_hash = ?)", owner);
        jdbc.update("delete from memory_profile_paragraphs where owner_hash = ?", owner);
        Instant now = Instant.now();
        for (int position = 0; position < paragraphs.size(); position++) {
            ProfileParagraph paragraph = paragraphs.get(position);
            String id = UUID.randomUUID().toString();
            jdbc.update("insert into memory_profile_paragraphs "
                            + "(id, owner_hash, owner_memory_revision, content_revision, category, "
                            + "position, text, source_count, created_at, updated_at) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, owner, ownerMemoryRevision, contentRevision, paragraph.category(),
                    position, paragraph.text(), paragraph.sourceIds().size(),
                    Timestamp.from(now), Timestamp.from(now));
            for (UUID source : paragraph.sourceIds()) {
                jdbc.update("insert into memory_profile_sources (paragraph_id, memory_id) "
                        + "values (?, ?)", id, source.toString());
            }
        }
        jdbc.update("update memory_profiles "
                        + "set built_content_revision = ?, based_on_revision = ?, snapshot_text = ?, "
                        + "updated_at = ? where owner_hash = ?",
                contentRevision, ownerMemoryRevision, snapshotText, Timestamp.from(now), owner);
    }

    private static long revision(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? -1L : value;
    }

    private record StoredParagraph(String category, int position, String text,
            long ownerMemoryRevision, long contentRevision, int sourceCount, String memoryId) {}
}
