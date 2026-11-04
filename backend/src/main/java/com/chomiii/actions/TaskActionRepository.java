package com.chomiii.actions;

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
public class TaskActionRepository {
    private final JdbcTemplate jdbc;

    public TaskActionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Proposal(UUID id, String title, String status, Instant createdAt) {}
    public record Task(UUID id, String title, String status, Instant createdAt) {}

    public Proposal propose(String owner, String title) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("insert into task_proposals (id, owner_hash, title, status, created_at, updated_at) "
                        + "values (?, ?, ?, 'pending', ?, ?)",
                id.toString(), owner, title, Timestamp.from(now), Timestamp.from(now));
        return new Proposal(id, title, "pending", now);
    }

    /** Locks an owned proposal to prevent concurrent double execution. */
    public Optional<Proposal> lockProposal(String owner, UUID id) {
        return jdbc.query("select id, title, status, created_at from task_proposals "
                        + "where id = ? and owner_hash = ? for update",
                (row, index) -> proposal(row), id.toString(), owner).stream().findFirst();
    }

    public boolean markApproved(String owner, UUID proposal) {
        return jdbc.update("update task_proposals set status = 'approved', updated_at = ? "
                        + "where id = ? and owner_hash = ? and status = 'pending'",
                Timestamp.from(Instant.now()), proposal.toString(), owner) == 1;
    }

    public boolean reject(String owner, UUID proposal) {
        return jdbc.update("update task_proposals set status = 'rejected', updated_at = ? "
                        + "where id = ? and owner_hash = ? and status = 'pending'",
                Timestamp.from(Instant.now()), proposal.toString(), owner) == 1;
    }

    public Task createTask(String owner, Proposal proposal) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("insert into personal_tasks "
                        + "(id, owner_hash, proposal_id, title, status, created_at, updated_at) "
                        + "values (?, ?, ?, ?, 'open', ?, ?)",
                id.toString(), owner, proposal.id().toString(), proposal.title(),
                Timestamp.from(now), Timestamp.from(now));
        return new Task(id, proposal.title(), "open", now);
    }

    public Optional<Task> taskByProposal(String owner, UUID proposalId) {
        return jdbc.query("select id, title, status, created_at from personal_tasks "
                        + "where owner_hash = ? and proposal_id = ?",
                (row, index) -> task(row), owner, proposalId.toString()).stream().findFirst();
    }

    public List<Task> listTasks(String owner) {
        return jdbc.query("select id, title, status, created_at from personal_tasks "
                        + "where owner_hash = ? order by created_at desc, id desc limit 100",
                (row, index) -> task(row), owner);
    }

    public List<Proposal> pending(String owner) {
        return jdbc.query("select id, title, status, created_at from task_proposals "
                        + "where owner_hash = ? and status = 'pending' order by created_at desc, id desc limit 100",
                (row, index) -> proposal(row), owner);
    }

    public boolean complete(String owner, UUID id) {
        return jdbc.update("update personal_tasks set status = 'completed', updated_at = ? "
                        + "where id = ? and owner_hash = ? and status = 'open'",
                Timestamp.from(Instant.now()), id.toString(), owner) == 1;
    }

    public Optional<Task> findTask(String owner, UUID id) {
        return jdbc.query("select id, title, status, created_at from personal_tasks "
                        + "where id = ? and owner_hash = ?",
                (row, index) -> task(row), id.toString(), owner).stream().findFirst();
    }

    private static Proposal proposal(ResultSet row) throws SQLException {
        return new Proposal(UUID.fromString(row.getString("id")), row.getString("title"),
                row.getString("status"), row.getTimestamp("created_at").toInstant());
    }

    private static Task task(ResultSet row) throws SQLException {
        return new Task(UUID.fromString(row.getString("id")), row.getString("title"),
                row.getString("status"), row.getTimestamp("created_at").toInstant());
    }
}
