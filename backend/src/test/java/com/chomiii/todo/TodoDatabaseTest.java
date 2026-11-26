package com.chomiii.todo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "CHOMIII_DB_URL", matches = "jdbc:(postgresql|mysql):.*")
@SpringBootTest
@ActiveProfiles("local")
class TodoDatabaseTest {
    @Autowired TodoService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    private final List<String> owners = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String owner : owners) jdbc.update("delete from browser_sessions where owner_hash = ?", owner);
    }

    @Test
    void replayIsolationCompletionAndProvenance() {
        String a = owner();
        String b = owner();
        UUID conversation = UUID.randomUUID();
        jdbc.update("insert into conversations (id, owner_hash, title, created_at, updated_at) values (?, ?, ?, ?, ?)",
                conversation.toString(), a, "chat", Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        UUID key = UUID.randomUUID();
        var request = new TodoService.CreateTodo("  Buy milk ", "  tomorrow ",
                Instant.parse("2090-01-02T03:04:05.123456789Z"), "Africa/Harare", conversation, null);
        assertThatThrownBy(() -> service.create(b, key, request)).isInstanceOf(IllegalArgumentException.class);
        PersonalTodo created = service.create(a, key, request);
        assertThat(created.title()).isEqualTo("Buy milk");
        assertThat(created.dueAt()).isEqualTo(Instant.parse("2090-01-02T03:04:05.123456Z"));
        assertThat(service.create(a, key, request)).isEqualTo(created);
        assertThatThrownBy(() -> service.create(a, key, new TodoService.CreateTodo("Different", "tomorrow",
                request.dueAt(), request.timezone(), conversation, request.sourcePlanId())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.list(b, null, 100)).isEmpty();
        assertThatThrownBy(() -> service.complete(b, created.id())).isInstanceOf(IllegalArgumentException.class);
        PersonalTodo completed = service.complete(a, created.id());
        assertThat(completed.status()).isEqualTo("completed");
        assertThat(service.complete(a, created.id())).isEqualTo(completed);
        assertThat(service.create(a, key, request)).isEqualTo(completed);
        assertThat(service.list(a, null, 100)).containsExactly(completed);
        assertThat(service.list(a, null, 100).get(0).timezone()).isEqualTo("Africa/Harare");
    }

    @Test
    void outerTransactionFailureRollsBackCreate() {
        String owner = owner();
        UUID key = UUID.randomUUID();
        var request = new TodoService.CreateTodo("Task", null, null, null, null, null);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            service.create(owner, key, request);
            throw new IllegalStateException("failure after insert");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(service.list(owner, null, 100)).isEmpty();
        assertThat(service.create(owner, key, request).title()).isEqualTo("Task");
    }

    @Test
    void concurrentRetriesReturnOneRecord() throws Exception {
        String owner = owner();
        UUID key = UUID.randomUUID();
        var request = new TodoService.CreateTodo("Task", null, null, null, null, null);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return service.create(owner, key, request); });
            var second = pool.submit(() -> { start.await(); return service.create(owner, key, request); });
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS));
            assertThat(service.list(owner, null, 100)).hasSize(1);
        }
    }

    @Test
    void listFiltersStatusBeforeApplyingLimitAndNeverCrossesOwners() {
        String a = owner();
        String b = owner();
        var request = new TodoService.CreateTodo("Task", null, null, null, null, null);
        var first = service.create(a, UUID.randomUUID(), request);
        var second = service.create(a, UUID.randomUUID(), request);
        service.create(a, UUID.randomUUID(), request);
        service.create(b, UUID.randomUUID(), request);
        service.complete(a, first.id());
        service.complete(a, second.id());
        assertThat(service.list(a, null, 2)).hasSize(2);
        assertThat(service.list(a, TodoService.Status.OPEN, 1))
                .singleElement().extracting(PersonalTodo::status).isEqualTo("open");
        assertThat(service.list(a, TodoService.Status.COMPLETED, 100))
                .extracting(PersonalTodo::id).containsExactlyInAnyOrder(first.id(), second.id());
        assertThat(service.list(b, TodoService.Status.COMPLETED, 100)).isEmpty();
    }

    @Test
    void maximumDueDateRoundTripsAtMicrosecondPrecision() {
        String owner = owner();
        var request = new TodoService.CreateTodo("Future", null,
                Instant.parse("9999-12-31T23:59:59.999999Z"), "Pacific/Auckland", null, null);
        var saved = service.create(owner, UUID.randomUUID(), request);
        assertThat(service.list(owner, null, 1)).containsExactly(saved);
    }

    @Test
    void planProvenanceMustBelongToTheOwnerAndConversation() {
        String a = owner();
        String b = owner();
        UUID aConversation = conversation(a);
        UUID bConversation = conversation(b);
        UUID ownedPlan = plan(a, aConversation);
        UUID foreignPlan = plan(b, bConversation);

        assertThatThrownBy(() -> service.create(a, UUID.randomUUID(),
                new TodoService.CreateTodo("Task", null, null, null, aConversation, foreignPlan)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(a, UUID.randomUUID(),
                new TodoService.CreateTodo("Task", null, null, null, aConversation, UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);
        var saved = service.create(a, UUID.randomUUID(),
                new TodoService.CreateTodo("Task", null, null, null, aConversation, ownedPlan));
        assertThat(saved.sourcePlanId()).isEqualTo(ownedPlan);
    }

    @Test
    void plannedCreateReusesOnlyMatchingOpenOwnedTasks() {
        String a = owner();
        String b = owner();
        UUID conversation = conversation(a);
        UUID plan = plan(a, conversation);
        var standalone = new TodoService.CreateTodo("Task", "note", Instant.parse("2030-01-01T00:00:00Z"), "UTC", null, null);
        var existing = service.create(a, UUID.randomUUID(), standalone);
        service.create(b, UUID.randomUUID(), standalone);
        var planned = new TodoService.CreateTodo(" Task ", " note ", standalone.dueAt(), "UTC", conversation, plan);
        assertThat(service.create(a, UUID.randomUUID(), planned).id()).isEqualTo(existing.id());
        assertThat(service.list(a, null, 100)).hasSize(1);
        assertThat(service.create(a, UUID.randomUUID(), standalone).id()).isNotEqualTo(existing.id());
        service.complete(a, existing.id());
        assertThat(service.create(a, UUID.randomUUID(), planned).id()).isNotEqualTo(existing.id());
    }

    @Test
    void agendaContextIncludesOnlyBoundedOpenOwnedTodosWithLocalTimes() {
        String a = owner();
        String b = owner();
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        var dueNow = todo(a, "Boundary task", now, "UTC");
        todo(a, "Harare appointment", Instant.parse("2030-01-02T10:30:00Z"), "Africa/Harare");
        todo(a, "Old task", now.minusSeconds(1), "UTC");
        var completed = todo(a, "Completed task", now.plusSeconds(60), "UTC");
        service.complete(a, completed.id());
        todo(b, "Foreign task", now.plusSeconds(60), "UTC");
        Instant horizon = now.plusSeconds(30L * 24 * 60 * 60);
        todo(a, "Horizon boundary", horizon, "UTC");
        todo(a, "Beyond horizon", horizon.plusSeconds(1), "UTC");

        String context = service.agendaContext(a, now);

        assertThat(context).contains("Boundary task — due 2030-01-01 00:00 UTC [UTC]")
                .contains("Harare appointment — due 2030-01-02 12:30 CAT [Africa/Harare]")
                .contains("Horizon boundary — due 2030-01-31 00:00 UTC [UTC]")
                .doesNotContain("Old task", "Completed task", "Foreign task", "Beyond horizon");
        assertThat(dueNow.dueAt()).isEqualTo(now);
    }

    private PersonalTodo todo(String owner, String title, Instant due, String zone) {
        return service.create(owner, UUID.randomUUID(), new TodoService.CreateTodo(title, null, due, zone, null, null));
    }

    private UUID conversation(String owner) {
        UUID id = UUID.randomUUID();
        var now = Timestamp.from(Instant.now());
        jdbc.update("insert into conversations (id, owner_hash, title, created_at, updated_at) values (?, ?, ?, ?, ?)",
                id.toString(), owner, "chat", now, now);
        return id;
    }

    private UUID plan(String owner, UUID conversation) {
        UUID id = UUID.randomUUID();
        var now = Timestamp.from(Instant.now());
        jdbc.update("insert into plan_executions (id, owner_hash, conversation_id, proposal_json, state, version, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
                id.toString(), owner, conversation.toString(), "{}", "PROPOSED", 0, now, now);
        return id;
    }

    private String owner() {
        String owner = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("insert into browser_sessions (owner_hash, created_at) values (?, ?)",
                owner, Timestamp.from(Instant.now()));
        owners.add(owner);
        return owner;
    }
}
