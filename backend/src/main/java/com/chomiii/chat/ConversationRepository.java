package com.chomiii.chat;

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
public class ConversationRepository {
    private final JdbcTemplate jdbc;

    public ConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void create(UUID id, String owner, Instant now) {
        jdbc.update("insert into conversations (id, owner_hash, title, created_at, updated_at) values (?, ?, ?, ?, ?)",
                id.toString(), owner, "New chat", Timestamp.from(now), Timestamp.from(now));
    }

    public List<ConversationDto.Summary> list(String owner) {
        return jdbc.query("select id, title, created_at, updated_at from conversations "
                        + "where owner_hash = ? order by updated_at desc, id desc",
                (row, index) -> summary(row), owner);
    }

    public Optional<ConversationDto.Summary> find(UUID id, String owner) {
        return jdbc.query("select id, title, created_at, updated_at from conversations where id = ? and owner_hash = ?",
                (row, index) -> summary(row), id.toString(), owner).stream().findFirst();
    }

    public List<ConversationDto.Message> messages(UUID id, String owner) {
        return jdbc.query("select m.id, m.role, m.content, m.status, m.created_at from messages m "
                        + "join conversations c on c.id = m.conversation_id "
                        + "where c.id = ? and c.owner_hash = ? order by m.sequence_no",
                (row, index) -> new ConversationDto.Message(UUID.fromString(row.getString("id")),
                        row.getString("role"), row.getString("content"), row.getString("status"),
                        row.getTimestamp("created_at").toInstant()), id.toString(), owner);
    }

    public boolean delete(UUID id, String owner) {
        return jdbc.update("delete from conversations where id = ? and owner_hash = ?", id.toString(), owner) == 1;
    }

    private static ConversationDto.Summary summary(ResultSet row) throws SQLException {
        return new ConversationDto.Summary(UUID.fromString(row.getString("id")), row.getString("title"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }
}
