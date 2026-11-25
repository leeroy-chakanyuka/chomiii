package com.chomiii.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chomiii.approval.ApprovalFacade;
import com.chomiii.chat.ChatStreamController;
import com.chomiii.chat.ChatStreamService;
import com.chomiii.chat.ChatTurnStore;
import com.chomiii.chat.ConversationRepository;
import com.chomiii.memory.MemoryLearningService;
import com.chomiii.memory.MemoryService;
import com.chomiii.planning.execution.PlanExecution;
import com.chomiii.planning.execution.PlanExecutionService;
import com.chomiii.planning.execution.PlanRunner;
import com.chomiii.provider.ChatProvider;
import com.chomiii.provider.ProviderCatalog;
import com.chomiii.todo.TodoService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@EnabledIfEnvironmentVariable(named = "EVAL_DISPOSABLE_DB", matches = "true")
@EnabledIfSystemProperty(named = "eval.mode", matches = "integration|live")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class PersonaExamPlanDatabaseTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MemoryService memories;
    @Autowired MemoryLearningService learning;
    @Autowired ConversationRepository conversations;
    @Autowired ChatTurnStore store;
    @Autowired PlanExecutionService plans;
    @Autowired PlanRunner runner;
    @Autowired ApprovalFacade approvals;
    @Autowired TodoService todos;

    private String owner;
    private String other;
    private UUID conversation;

    @BeforeEach
    void setUp() {
        DisposableEvaluationDatabase.validate(System.getenv());
        owner = ownerId();
        other = ownerId();
        var now = Instant.now();
        jdbc.update("insert into browser_sessions (owner_hash, created_at) values (?, ?), (?, ?)",
                owner, Timestamp.from(now), other, Timestamp.from(now));
        conversation = UUID.randomUUID();
        conversations.create(conversation, owner, now);
        PersonaSeed.facts(memories, owner, FictionalPersona.durable().stream()
                .filter(fact -> fact.id().equals("education-exams") || fact.id().equals("education-course"))
                .toList());
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from browser_sessions where owner_hash in (?, ?)", owner, other);
    }

    @Test
    void savedExamContextBecomesAReviewedOwnerScopedPlan() throws Exception {
        String proposal = """
                {"goal":"Prepare for certificate exams","summary":"Review existing tasks and prepare for study.",
                 "memoryReferences":[],"constraints":[],"missingInformation":[],"steps":[
                 {"id":"read","title":"Review tasks","capability":"todo.list","dependsOn":[],"arguments":{}},
                 {"id":"write","title":"Prepare study materials","capability":"todo.create","dependsOn":["read"],"arguments":{"title":"Prepare certificate exam study materials"}},
                 {"id":"after","title":"Review updated tasks","capability":"todo.list","dependsOn":["write"],"arguments":{}}]}
                """;
        var input = new AtomicReference<ChatProvider.ChatInput>();
        ChatProvider fake = new ChatProvider() {
            @Override public String id() { return "gemini"; }
            @Override public Generation open(ChatInput value, String key) {
                input.set(value);
                return new Generation() {
                    @Override public void stream(Consumer<String> delta) { delta.accept(proposal); }
                    @Override public void close() { }
                };
            }
        };
        String request = "Plan certificate exams and Saturday study preparation.";
        try (var executor = Executors.newSingleThreadExecutor()) {
            var chat = new ChatStreamService(store, conversations, memories, learning,
                    new ProviderCatalog(List.of("model")), List.of(fake), executor);
            chat.start(conversation, owner, new ChatStreamController.MessageRequest(
                    UUID.randomUUID(), "gemini", "model", request, true), "fake-key", true);
            executor.submit(() -> {}).get(20, TimeUnit.SECONDS);
        }

        assertThat(input.get()).isNotNull();
        assertThat(input.get().messages()).extracting(ChatProvider.Message::text)
                .anySatisfy(text -> assertThat(text).contains("certificate exams", "Saturday afternoons"));
        var messages = conversations.messages(conversation, owner);
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).status()).isEqualTo("complete");
        assertThat(messages.get(1).content()).contains("No to-do changes have been made").doesNotContain("Proposed plan");
        assertThat(conversations.messages(conversation, other)).isEmpty();
        assertThat(plans.list(owner, conversation)).hasSize(1);
        var plan = plans.list(owner, conversation).getFirst();
        var id = plan.proposal().id();
        assertThat(plans.list(other, conversation)).isEmpty();
        assertThatThrownBy(() -> plans.find(other, id)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runner.run(other, conversation, id)).isInstanceOf(IllegalArgumentException.class);

        var waiting = runner.run(owner, conversation, id);
        assertThat(waiting.steps()).extracting(PlanExecution.Step::state).containsExactly(
                PlanExecution.State.COMPLETED, PlanExecution.State.AWAITING_APPROVAL, PlanExecution.State.PROPOSED);
        assertThat(todos.list(owner, null, 100)).isEmpty();
        var approvalId = waiting.steps().get(1).pendingApprovalId();
        assertThat(approvalId).isNotNull();
        assertThat(approvals.list(owner, conversation)).anySatisfy(approval -> {
            assertThat(approval.id()).isEqualTo(approvalId);
            assertThat(approval.preview()).contains("Prepare certificate exam study materials");
        });
        assertThatThrownBy(() -> approvals.approve(other, conversation, approvalId)).isInstanceOf(RuntimeException.class);
        assertThat(todos.list(other, null, 100)).isEmpty();
        approvals.approve(owner, conversation, approvalId);
        approvals.approve(owner, conversation, approvalId);
        var saved = todos.list(owner, null, 100);
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().title()).isEqualTo("Prepare certificate exam study materials");
        assertThat(saved.getFirst().sourceConversationId()).isEqualTo(conversation);
        assertThat(saved.getFirst().sourcePlanId()).isEqualTo(id);
        assertThatThrownBy(() -> todos.complete(other, saved.getFirst().id())).isInstanceOf(IllegalArgumentException.class);
        var completed = runner.run(owner, conversation, id);
        assertThat(completed.state()).isEqualTo(PlanExecution.State.COMPLETED);
        assertThat(completed.steps()).extracting(PlanExecution.Step::state).containsExactly(
                PlanExecution.State.COMPLETED, PlanExecution.State.COMPLETED, PlanExecution.State.COMPLETED);
        assertThat(todos.list(owner, null, 100)).hasSize(1);
    }

    private static String ownerId() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }
}
