package com.chomiii.planning.commitment;

import com.chomiii.memory.MemoryRepository;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short-lived scheduling context, never an instruction or an external action. */
@Service
@EnableScheduling
public class SpokenSlotService {
    private static final Pattern CHANGE = Pattern.compile("(?i)\\b(cancel|cancelled|change|changed|instead|"
            + "reschedule|different|forget|actually|not|never|no longer|don['’]t|didn['’]t|can['’]t|won['’]t|"
            + "taking|choosing|\\d{1,2}(?::[0-5]\\d)?\\s*(?:am|pm))\\b");
    private final JdbcTemplate jdbc;
    private final MemoryRepository memories;
    private final Clock clock;

    public record Choice(String activity, LocalDate date, LocalTime time, String zoneId, Instant expiresAt) {}

    public static boolean isSelection(String text) { return SlotChoiceParser.isSelection(text); }

    /** Resolve choices in their source conversation, including later corrections there. */
    @Transactional
    public List<Choice> agenda(String owner, Long revision) {
        if (revision == null) return List.of();
        var sources = jdbc.query("select conversation_id from spoken_slot_choices where owner_hash = ? and memory_revision = ? "
                        + "and expires_at > ? group by conversation_id order by max(captured_at) desc limit 5",
                (r, i) -> UUID.fromString(r.getString(1)), owner, revision, Timestamp.from(clock.instant()));
        return sources.stream().flatMap(id -> current(owner, id, revision, true).stream()).toList();
    }
    private record Turn(String id, long sequence, String content, Instant createdAt, Long revision, String assistantStatus) {}

    @Autowired
    public SpokenSlotService(JdbcTemplate jdbc, MemoryRepository memories) {
        this(jdbc, memories, Clock.systemUTC());
    }

    SpokenSlotService(JdbcTemplate jdbc, MemoryRepository memories, Clock clock) {
        this.jdbc = jdbc;
        this.memories = memories;
        this.clock = clock;
    }

    /** Call after the selected turn completes, using that turn's captured revision and browser zone. */
    @Transactional
    public Optional<Choice> capture(String owner, UUID conversation, UUID request, Long revision,
            String browserZoneId, boolean memoryEnabled) {
        if (!memoryEnabled || owner == null || conversation == null || request == null || revision == null) return Optional.empty();
        memories.lockOwner(owner);
        if (memories.revision(owner) != revision) return Optional.empty();
        var current = jdbc.query("select u.id, u.sequence_no, u.content, u.created_at, u.memory_revision_at_turn, a.status as assistant_status "
                        + "from messages u join conversations c on c.id = u.conversation_id "
                        + "left join messages a on a.conversation_id = u.conversation_id and a.request_id = u.request_id and a.role = 'assistant' "
                        + "where c.owner_hash = ? and c.id = ? and u.request_id = ? and u.role = 'user'",
                (row, index) -> turn(row), owner, conversation.toString(), request.toString());
        if (current.size() != 1 || !eligible(current.get(0), revision)) return Optional.empty();
        Turn selected = current.get(0);
        var parsed = SlotChoiceParser.parse(selected.content(), selected.createdAt(), null, null, browserZoneId);
        Turn prior = null;
        if (parsed.isEmpty()) {
            var previous = jdbc.query("select u.id, u.sequence_no, u.content, u.created_at, u.memory_revision_at_turn, a.status as assistant_status "
                        + "from messages u left join messages a on a.conversation_id = u.conversation_id "
                        + "and a.request_id = u.request_id and a.role = 'assistant' "
                        + "where u.conversation_id = ? and u.role = 'user' and u.sequence_no < ? order by u.sequence_no desc limit 1",
                    (row, index) -> turn(row), conversation.toString(), selected.sequence());
            prior = previous.isEmpty() ? null : previous.get(0);
            if (prior != null && !eligible(prior, revision)) return Optional.empty();
            parsed = SlotChoiceParser.parse(selected.content(), selected.createdAt(), prior == null ? null : prior.content(),
                    prior == null ? null : prior.createdAt(), browserZoneId);
        }
        if (parsed.isEmpty() || !parsed.get().expiresAt().isAfter(clock.instant())) return Optional.empty();
        var choice = parsed.get();
        jdbc.update("delete from spoken_slot_choices where owner_hash = ? and expires_at <= ?", owner, Timestamp.from(clock.instant()));
        var existing = jdbc.query("select activity, local_date, local_time, zone_id, expires_at from spoken_slot_choices where message_id = ?",
                (row, index) -> new Choice(row.getString("activity"), row.getDate("local_date").toLocalDate(),
                        row.getTime("local_time").toLocalTime(), row.getString("zone_id"),
                        row.getTimestamp("expires_at").toInstant()), selected.id());
        if (existing.isEmpty()) {
            Integer count = jdbc.queryForObject("select count(*) from spoken_slot_choices where owner_hash = ?", Integer.class, owner);
            if (count == null || count >= 20) return Optional.empty();
            jdbc.update("insert into spoken_slot_choices (message_id, context_message_id, owner_hash, conversation_id, memory_revision, activity, local_date, local_time, zone_id, captured_at, expires_at) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", selected.id(), prior == null ? null : prior.id(), owner, conversation.toString(), revision,
                    choice.activity(), Date.valueOf(choice.date()), Time.valueOf(choice.time()), choice.zone().getId(),
                    Timestamp.from(selected.createdAt()), Timestamp.from(choice.expiresAt()));
        } else {
            return Optional.of(existing.get(0));
        }
        return Optional.of(new Choice(choice.activity(), choice.date(), choice.time(), choice.zone().getId(), choice.expiresAt()));
    }

    /** Read only within the same conversation and current memory revision. */
    @Transactional
    public Optional<Choice> current(String owner, UUID conversation, Long revision, boolean memoryEnabled) {
        if (!memoryEnabled || owner == null || conversation == null || revision == null) return Optional.empty();
        memories.lockOwner(owner);
        if (memories.revision(owner) != revision) return Optional.empty();
        var choices = jdbc.query("select s.activity, s.local_date, s.local_time, s.zone_id, s.expires_at, m.sequence_no "
                        + "from spoken_slot_choices s join conversations c on c.id = s.conversation_id "
                        + "join messages m on m.id = s.message_id where s.owner_hash = ? and c.owner_hash = ? "
                        + "and s.conversation_id = ? and s.memory_revision = ? and s.expires_at > ? "
                        + "order by m.sequence_no desc limit 1",
                (row, index) -> new Current(new Choice(row.getString("activity"), row.getDate("local_date").toLocalDate(),
                        row.getTime("local_time").toLocalTime(), row.getString("zone_id"),
                        row.getTimestamp("expires_at").toInstant()), row.getLong("sequence_no")),
                owner, owner, conversation.toString(), revision, Timestamp.from(clock.instant()));
        if (choices.isEmpty()) return Optional.empty();
        Current latest = choices.get(0);
        List<String> later = jdbc.query("select content from messages where conversation_id = ? and role = 'user' "
                        + "and sequence_no > ? order by sequence_no desc limit 21",
                (row, index) -> row.getString(1), conversation.toString(), latest.sequence());
        if (later.size() > 20 || later.stream().anyMatch(text -> CHANGE.matcher(text).find())) return Optional.empty();
        return Optional.of(latest.choice());
    }

    /** Remove expired and revision-invalid rows, including those of inactive owners. */
    @Scheduled(initialDelay = 3_600_000, fixedDelay = 3_600_000)
    @Transactional
    public int purgeExpired() {
        return jdbc.update("delete from spoken_slot_choices where expires_at <= ? or not exists "
                        + "(select 1 from browser_sessions b where b.owner_hash = spoken_slot_choices.owner_hash "
                        + "and b.memory_revision = spoken_slot_choices.memory_revision)", Timestamp.from(clock.instant()));
    }

    private record Current(Choice choice, long sequence) {}

    private static Turn turn(java.sql.ResultSet row) throws java.sql.SQLException {
        Number revision = (Number) row.getObject("memory_revision_at_turn");
        return new Turn(row.getString("id"), row.getLong("sequence_no"), row.getString("content"),
                row.getTimestamp("created_at").toInstant(), revision == null ? null : revision.longValue(),
                row.getString("assistant_status"));
    }

    private static boolean eligible(Turn turn, long revision) {
        return turn.revision() != null && turn.revision() == revision && "complete".equals(turn.assistantStatus());
    }
}
