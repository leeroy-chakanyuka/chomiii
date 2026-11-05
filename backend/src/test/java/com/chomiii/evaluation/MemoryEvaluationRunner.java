package com.chomiii.evaluation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chomiii.chat.ChatStreamController;
import com.chomiii.chat.ChatStreamService;
import com.chomiii.chat.ChatTurnStore;
import com.chomiii.chat.ContextWindow;
import com.chomiii.chat.ConversationRepository;
import com.chomiii.memory.MemoryCandidateParser;
import com.chomiii.memory.MemoryService;
import com.chomiii.memory.PersonalMemory;
import com.chomiii.provider.ProviderCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class MemoryEvaluationRunner {
    record TurnObservation(String turnId, String expectedEligibility, String observedEligibility,
            boolean extractionAttempted, int rawCandidates, int acceptedCandidates, int stored,
            String validationOutcome, String rawOutput, Long extractionMillis) {}
    record StoredMemory(String id, String content, String source, String status, double confidence) {}
    record RetrievalObservation(String query, List<String> selectedMemoryIds, List<String> expectedMemoryIds) {}
    record Result(String fixtureId, String mode, List<TurnObservation> turns, List<StoredMemory> memories,
            List<RetrievalObservation> retrievals, MemoryScorer.Score score, List<String> limitations) {}

    Result runOffline(EvaluationFixture fixture) throws Exception {
        var json = new ObjectMapper();
        var repository = new InMemoryEvaluationRepository();
        var memories = new MemoryService(repository);
        var extractions = new HashMap<String, String>();
        var answers = new HashMap<String, String>();
        for (int i = 0; i < fixture.turns().size(); i++) {
            var turn = fixture.turns().get(i);
            if (!"user".equals(turn.role())) continue;
            if (turn.scriptedExtraction() != null) extractions.put(turn.content().strip(), turn.scriptedExtraction().toString());
            if (i + 1 < fixture.turns().size() && "assistant".equals(fixture.turns().get(i + 1).role())) {
                answers.put(turn.content().strip(), fixture.turns().get(i + 1).content());
            }
        }
        var provider = new EvaluationProvider(extractions, answers);
        var store = scriptedTurnStore();
        String owner = "synthetic-owner";
        UUID conversation = UUID.randomUUID();
        var observations = new ArrayList<TurnObservation>();

        try (var chatWorker = Executors.newSingleThreadExecutor();
                var learningWorker = new EvaluationLearningService.CountingExecutor();
                var timer = Executors.newSingleThreadScheduledExecutor();
                var validators = Validation.buildDefaultValidatorFactory()) {
            var learning = new EvaluationLearningService(memories, json, learningWorker, timer);
            var chat = new ChatStreamService(store, mock(ConversationRepository.class), memories, learning,
                    new ProviderCatalog(List.of("gemini-3.5-flash-lite")), List.of(provider), chatWorker);
            for (var turn : fixture.turns()) {
                if (!"user".equals(turn.role())) continue;
                var request = new ChatStreamController.MessageRequest(UUID.randomUUID(), "gemini",
                        "gemini-3.5-flash-lite", turn.content());
                if (!validators.getValidator().validate(request).isEmpty()) {
                    observations.add(new TurnObservation(turn.id(), turn.expectedEligibility(), "chat_rejected",
                            false, 0, 0, 0, "not_applicable", null, null));
                    continue;
                }
                int before = memories.list(owner).size();
                int callsBefore = provider.calls().size();
                chat.start(conversation, owner, request, "offline-test-key");
                var submission = learning.awaitSubmission();
                learningWorker.submit(() -> {}).get(15, TimeUnit.SECONDS);
                var call = provider.calls().stream().skip(callsBefore)
                        .filter(entry -> "extraction".equals(entry.kind())).findFirst();
                String eligibility = eligibility(turn.content().strip(), submission);
                int rawCount = 0;
                int accepted = 0;
                String validation = "not_applicable";
                String raw = null;
                Long elapsed = null;
                if (call.isPresent()) {
                    raw = call.get().output();
                    elapsed = TimeUnit.NANOSECONDS.toMillis(call.get().durationNanos());
                    var root = json.readTree(raw);
                    rawCount = root.path("memories").isArray() ? root.path("memories").size() : 0;
                    accepted = MemoryCandidateParser.parse(raw, turn.content().strip(), json).size();
                    validation = rawCount == accepted ? "accepted_or_empty" : "rejected_reason_unavailable";
                } else if (submission.submitted()) {
                    validation = "provider_result_unavailable";
                }
                int stored = Math.max(0, memories.list(owner).size() - before);
                observations.add(new TurnObservation(turn.id(), turn.expectedEligibility(), eligibility,
                        call.isPresent(), rawCount, accepted, stored, validation, raw, elapsed));
            }
        }
        List<PersonalMemory> actual = memories.list(owner);
        var stored = actual.stream().map(memory -> new StoredMemory(memory.id().toString(), memory.content(),
                memory.source(), memory.status(), memory.confidence())).toList();
        var score = new MemoryScorer().score(fixture, stored.stream().map(memory ->
                new MemoryScorer.Actual(memory.id(), memory.content(), memory.status())).toList());
        var retrievals = fixture.retrievalChecks().stream().map(check -> {
            String context = memories.forChat(owner, check.query());
            List<String> selected = actual.stream().filter(memory -> context.contains("- "
                    + memory.content().replaceAll("\\s+", " ").strip() + " (source: "))
                    .map(memory -> memory.id().toString()).toList();
            return new RetrievalObservation(check.query(), selected, check.expectedMemoryIds());
        }).toList();
        return new Result(fixture.id(), "offline-scripted", observations, stored, retrievals, score,
                List.of("Scripted output is not a model-quality measurement.",
                        "The offline store does not verify JDBC, browser isolation, or database transactions.",
                        "Candidate rejection and skipped-write reasons are not exposed by the application.",
                        "Token usage, first visible text, and grounded answer accuracy are not measured."));
    }

    private static String eligibility(String text, EvaluationLearningService.Submission submission) {
        if (submission.submitted()) return "eligible";
        if (submission.rejected()) return "queue_full";
        if (text.length() < 25) return "too_short";
        if (text.length() > 2_000) return "too_long";
        return "no_personal_language";
    }

    private static ChatTurnStore scriptedTurnStore() {
        var store = mock(ChatTurnStore.class);
        Map<UUID, List<ContextWindow.Turn>> history = new HashMap<>();
        Map<UUID, String> pending = new HashMap<>();
        when(store.begin(any(), anyString(), any(), anyString())).thenAnswer(call -> {
            UUID conversation = call.getArgument(0);
            UUID request = call.getArgument(2);
            pending.put(request, call.getArgument(3));
            return new ChatTurnStore.Pending(UUID.randomUUID(),
                    List.copyOf(history.getOrDefault(conversation, List.of())));
        });
        doAnswer(call -> {
            UUID conversation = call.getArgument(0);
            UUID request = call.getArgument(2);
            var turns = history.computeIfAbsent(conversation, ignored -> new ArrayList<>());
            turns.add(new ContextWindow.Turn("user", pending.remove(request), "complete"));
            turns.add(new ContextWindow.Turn("assistant", call.getArgument(4), call.getArgument(5)));
            return null;
        }).when(store).finish(any(), anyString(), any(), any(), anyString(), anyString());
        return store;
    }
}
