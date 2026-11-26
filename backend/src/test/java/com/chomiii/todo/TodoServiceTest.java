package com.chomiii.todo;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.Test;

class TodoServiceTest {
    private final TodoRepository repository = mock(TodoRepository.class);
    private final TodoService service = new TodoService(repository);

    @Test
    void rejectsInvalidFieldsBeforeAccessingStorage() {
        UUID key = UUID.randomUUID();
        assertThatThrownBy(() -> service.create("owner", key, request("  ", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("owner", key, request("x".repeat(201), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("owner", key, request("x", "x".repeat(4001), null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("owner", key, request("x", null, null, "+02:00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("owner", key,
                request("x", null, Instant.parse("+10000-01-01T00:00:00Z"), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("owner", key, request("x", null, Instant.MAX, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("owner", key, request("x", null, Instant.EPOCH.minusNanos(1), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("owner", null, request("x", null, null, null)))
                .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsUnboundedOrEmptyListLimits() {
        assertThatThrownBy(() -> service.list("owner", null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list("owner", TodoService.Status.OPEN, TodoService.MAX_LIST_RESULTS + 1))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void agendaContextIncludesDueDateAndZoneAndMarksTodoNotCalendar() {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        Instant due = Instant.parse("2030-01-02T10:30:00Z");
        when(repository.upcoming(eq("owner"), eq(now), eq(now.plusSeconds(30L * 24 * 60 * 60)),
                eq(TodoService.MAX_CONTEXT_TODOS))).thenReturn(List.of(todo("Call dentist", due, "Africa/Harare")));

        String context = service.agendaContext("owner", now);

        org.assertj.core.api.Assertions.assertThat(context)
                .contains("Local to-dos (not booked calendar events)")
                .contains("Call dentist")
                .contains("2030-01-02 12:30 CAT [Africa/Harare]");
    }

    @Test
    void returnsNoContextWhenNoUpcomingTodosAndUsesThirtyDayExpiryBoundary() {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        when(repository.upcoming(eq("owner"), eq(now), eq(now.plusSeconds(30L * 24 * 60 * 60)),
                eq(TodoService.MAX_CONTEXT_TODOS))).thenReturn(List.of());

        org.assertj.core.api.Assertions.assertThat(service.agendaContext("owner", now)).isEmpty();
        org.mockito.Mockito.verify(repository).upcoming("owner", now, now.plusSeconds(30L * 24 * 60 * 60),
                TodoService.MAX_CONTEXT_TODOS);
    }

    private static PersonalTodo todo(String title, Instant due, String zone) {
        return new PersonalTodo(UUID.randomUUID(), title, null, "open", due, zone, null, null, null, null);
    }

    private static TodoService.CreateTodo request(String title, String description, Instant due, String zone) {
        return new TodoService.CreateTodo(title, description, due, zone, null, null);
    }
}
