package com.chomiii.todo;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TodoService {
    public static final int MAX_LIST_RESULTS = 100;
    public static final int MAX_CONTEXT_TODOS = 10;
    private static final java.time.Duration CONTEXT_HORIZON = java.time.Duration.ofDays(30);
    public enum Status { OPEN, COMPLETED }
    private final TodoRepository repository;

    public TodoService(TodoRepository repository) {
        this.repository = repository;
    }

    /** Owner, key and provenance must come from trusted server context, never model arguments. */
    public record CreateTodo(String title, String description, Instant dueAt, String timezone,
            UUID sourceConversationId, UUID sourcePlanId) { }
    public record CreateResult(PersonalTodo todo, String outcome) { }

    @Transactional
    public PersonalTodo create(String owner, UUID idempotencyKey, CreateTodo request) {
        return createWithOutcome(owner, idempotencyKey, request).todo();
    }

    @Transactional
    public CreateResult createWithOutcome(String owner, UUID idempotencyKey, CreateTodo request) {
        Objects.requireNonNull(idempotencyKey, "idempotency key");
        CreateTodo canonical = normalize(request);
        repository.lockOwner(owner);
        var existing = repository.findByKey(owner, idempotencyKey);
        if (existing.isPresent()) {
            PersonalTodo todo = existing.get();
            if (!canonical.equals(new CreateTodo(todo.title(), todo.description(), todo.dueAt(), todo.timezone(),
                    todo.sourceConversationId(), todo.sourcePlanId()))) {
                throw new IllegalStateException("Idempotency key already used for a different todo");
            }
            return new CreateResult(todo, "created");
        }
        if (canonical.sourceConversationId() != null
                && !repository.ownsConversation(owner, canonical.sourceConversationId())) {
            throw new IllegalArgumentException("Source conversation not found");
        }
        if (canonical.sourcePlanId() != null
                && !repository.ownsPlan(owner, canonical.sourcePlanId(), canonical.sourceConversationId())) {
            throw new IllegalArgumentException("Source plan not found");
        }
        if (canonical.sourcePlanId() != null) {
            var matching = repository.findMatchingOpen(owner, canonical);
            if (matching.isPresent()) return new CreateResult(matching.get(), "already_exists");
        }
        return new CreateResult(repository.create(owner, idempotencyKey, canonical), "created");
    }

    public static CreateTodo normalize(CreateTodo request) {
        Objects.requireNonNull(request, "request");
        String title = text(request.title(), 200, true);
        String description = text(request.description(), 4000, false);
        String timezone = text(request.timezone(), 100, false);
        if (timezone != null && !ZoneId.getAvailableZoneIds().contains(timezone)) {
            throw new IllegalArgumentException("Invalid IANA timezone");
        }
        Instant due = request.dueAt();
        if (due != null && (due.isBefore(Instant.EPOCH)
                || !due.isBefore(Instant.parse("+10000-01-01T00:00:00Z")))) {
            throw new IllegalArgumentException("Due timestamp out of range");
        }
        if (due != null) due = due.truncatedTo(ChronoUnit.MICROS);
        return new CreateTodo(title, description, due, timezone,
                request.sourceConversationId(), request.sourcePlanId());
    }

    @Transactional(readOnly = true)
    public List<PersonalTodo> list(String owner, Status status, int limit) {
        if (limit < 1 || limit > MAX_LIST_RESULTS) {
            throw new IllegalArgumentException("Invalid todo list limit");
        }
        return repository.list(owner, status, limit);
    }

    /** Returns bounded agenda context for a scheduling turn; entries are local to-dos, not bookings. */
    @Transactional(readOnly = true)
    public String agendaContext(String owner, Instant now) {
        Objects.requireNonNull(now, "now");
        List<PersonalTodo> todos = repository.upcoming(owner, now, now.plus(CONTEXT_HORIZON), MAX_CONTEXT_TODOS);
        if (todos.isEmpty()) return "";
        StringBuilder context = new StringBuilder("Local to-dos (not booked calendar events):\n");
        for (PersonalTodo todo : todos) {
            ZoneId zone = ZoneId.of(todo.timezone() == null ? "UTC" : todo.timezone());
            context.append("- ").append(todo.title()).append(" — due ")
                    .append(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm z' ['VV']'").format(todo.dueAt().atZone(zone)))
                    .append('\n');
        }
        return context.toString().stripTrailing();
    }

    @Transactional
    public PersonalTodo complete(String owner, UUID id) {
        Objects.requireNonNull(id, "id");
        repository.lockOwner(owner);
        PersonalTodo todo = repository.find(owner, id)
                .orElseThrow(() -> new IllegalArgumentException("Todo not found"));
        if ("completed".equals(todo.status())) return todo;
        repository.complete(owner, id);
        return repository.find(owner, id).orElseThrow();
    }

    private static String text(String value, int max, boolean required) {
        String clean = value == null ? null : value.strip();
        if (clean == null || clean.isEmpty()) {
            if (required) throw new IllegalArgumentException("Title is required");
            return null;
        }
        if (clean.length() > max) throw new IllegalArgumentException("Todo field is too long");
        return clean;
    }
}
