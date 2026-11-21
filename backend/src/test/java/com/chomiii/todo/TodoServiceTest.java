package com.chomiii.todo;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.UUID;
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

    private static TodoService.CreateTodo request(String title, String description, Instant due, String zone) {
        return new TodoService.CreateTodo(title, description, due, zone, null, null);
    }
}
