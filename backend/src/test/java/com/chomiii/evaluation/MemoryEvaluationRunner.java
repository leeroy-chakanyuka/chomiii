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
import com.chomiii.provider.ChatProvider;
import com.chomiii.provider.ProviderCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class MemoryEvaluationRunner {
    @FunctionalInterface interface IsolationCheck { boolean test(UUID memoryId) throws Exception; }
    record TurnObservation(String turnId, String expectedEligibility, String observedEligibility,
            boolean extractionAttempted, int rawCandidates, int acceptedCandidates, int stored,
            String validationOutcome, String rawOutput, boolean rawOutputTruncated, List<CandidateObservation> candidates,
            Long requestProcessingMillis, Long firstProviderTokenMillis, Long mainCompletionMillis,
            Long schedulingDelayMillis, Long extractionMillis, String failure) {}
    record CandidateObservation(String content, String kind, Double confidence, String evidence,
            Boolean literalEvidenceFound, boolean accepted) {}
    record StoredMemory(String id, String content, String source, String status, double confidence,
            String sourceConversationId) {}
    record RetrievalObservation(String id, String query, List<String> selectedMemoryIds, List<String> expectedMemoryIds,
            long retrievalMillis, String answer, boolean contextAsUser, boolean memoryInSystem,
            List<StoredMemory> learnedAfterAnswer, boolean answerTruncated, String failure) {}
    record LifecycleObservation(String operation, String memoryId, String outcome) {}
    record Result(String fixtureId, String mode, List<TurnObservation> turns, List<StoredMemory> memories,
            List<RetrievalObservation> retrievals, List<LifecycleObservation> lifecycle,
            MemoryScorer.Score extractionScore, MemoryScorer.Score score, List<String> limitations) {}
    record PlanningMemory(Result observation, List<PersonalMemory> selected, long retrievalMillis) {}

    Result runOffline(EvaluationFixture fixture) throws Exception {
        return run(fixture, new MemoryService(new InMemoryEvaluationRepository()), scriptedTurnStore(),
                mock(ConversationRepository.class), "synthetic-owner", "synthetic-other-owner", UUID.randomUUID(),
                id -> true, null, null, "gemini-3.5-flash-lite", "offline-test-key", 15, "offline-scripted");
    }

    Result runDatabase(EvaluationFixture fixture, DisposableEvaluationDatabase database,
            ChatProvider liveProvider, LiveBudget budget, String model, String apiKey, int timeoutSeconds) throws Exception {
        if (liveProvider != null && (budget == null || apiKey == null || apiKey.isBlank() || timeoutSeconds < 1)) {
            throw new IllegalArgumentException("Live evaluation needs an enabled budget, a key, and a timeout");
        }
        try (var session = database.session()) {
            return run(fixture, database.memories(), database.turns(), database.conversations(),
                    session.owner(), session.other(), session.conversation(), id -> database.apiIsolation(session, id), liveProvider, budget,
                    model, apiKey, timeoutSeconds, liveProvider == null ? "database-scripted" : "live");
        }
    }

    PlanningMemory runForPlanning(EvaluationFixture fixture, DisposableEvaluationDatabase database,
            ChatProvider liveProvider, LiveBudget budget, String model, String apiKey, int timeoutSeconds,
            String question) throws Exception {
        if (database != null) {
            try (var session = database.session()) {
                Result result = run(fixture, database.memories(), database.turns(), database.conversations(),
                        session.owner(), session.other(), session.conversation(), id -> database.apiIsolation(session, id),
                        liveProvider, budget, model, apiKey, timeoutSeconds,
                        liveProvider == null ? "database-scripted" : "live");
                long started = System.nanoTime();
                var selected = database.memories().forPlanning(session.owner(), question);
                return new PlanningMemory(result, selected, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
        }
        var memories = new MemoryService(new InMemoryEvaluationRepository());
        Result result = run(fixture, memories, scriptedTurnStore(), mock(ConversationRepository.class),
                "synthetic-owner", "synthetic-other-owner", UUID.randomUUID(), id -> true,
                liveProvider, budget, model, apiKey, timeoutSeconds,
                liveProvider == null ? "offline-scripted" : "live-in-memory");
        long started = System.nanoTime();
        var selected = memories.forPlanning("synthetic-owner", question);
        return new PlanningMemory(result, selected, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    private Result run(EvaluationFixture fixture, MemoryService memories, ChatTurnStore turnStore,
            ConversationRepository conversations, String owner, String otherOwner, UUID conversation,
            IsolationCheck isolation, ChatProvider liveProvider, LiveBudget budget, String model, String apiKey, int timeoutSeconds,
            String mode) throws Exception {
        var json = new ObjectMapper();
        var extractions = new HashMap<String, String>();
        var answers = new HashMap<String, String>();
        for (int i = 0; i < fixture.turns().size(); i++) {
            var turn = fixture.turns().get(i);
            if (!"user".equals(turn.role())) continue;
            if (turn.scriptedExtraction() != null) extractions.put(turn.content().strip(),
                    turn.scriptedExtraction().isTextual() ? turn.scriptedExtraction().asText() : turn.scriptedExtraction().toString());
            if (i + 1 < fixture.turns().size() && "assistant".equals(fixture.turns().get(i + 1).role())) {
                answers.put(turn.content().strip(), fixture.turns().get(i + 1).content());
            }
        }
        var observations = new ArrayList<TurnObservation>();
        var lifecycle = new ArrayList<LifecycleObservation>();
        var extracted = new ArrayList<MemoryScorer.Actual>();
        var retrievals = new ArrayList<RetrievalObservation>();
        List<PersonalMemory> actual;

        try (var chatWorker = Executors.newSingleThreadExecutor();
                var learningWorker = new EvaluationLearningService.CountingExecutor();
                var timer = Executors.newSingleThreadScheduledExecutor();
                var validators = Validation.buildDefaultValidatorFactory()) {
            var provider = new EvaluationProvider(extractions, answers, liveProvider, budget, timer, timeoutSeconds);
            var learning = new EvaluationLearningService(memories, json, learningWorker, timer);
            var store = new EvaluationTurnStore(turnStore);
            var chat = new ChatStreamService(store, conversations, memories, learning,
                    new ProviderCatalog(List.of(model)), List.of(provider), chatWorker);
            for (var turn : fixture.turns()) {
                if (!"user".equals(turn.role())) continue;
                var request = new ChatStreamController.MessageRequest(UUID.randomUUID(), "gemini", model, turn.content(), false);
                if (!validators.getValidator().validate(request).isEmpty()) {
                    observations.add(new TurnObservation(turn.id(), turn.expectedEligibility(), "chat_rejected",
                            false, 0, 0, 0, "not_applicable", null, false, List.of(), null, null, null, null, null, null));
                    continue;
                }
                var before = memories.list(owner).stream().map(PersonalMemory::id).collect(java.util.stream.Collectors.toSet());
                int callsBefore = provider.calls().size();
                var actions = fixture.lifecycle().stream().filter(action -> turn.id().equals(action.afterTurnId())).toList();
                var pendingDeletion = actions.stream().filter(action -> "delete_while_pending".equals(action.operation()))
                        .findFirst().orElse(null);
                if (pendingDeletion != null) provider.blockExtraction(turn.content().strip());
                long requestStarted = System.nanoTime();
                chat.start(conversation, owner, request, apiKey);
                long processingMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestStarted);
                var completion = store.awaitCompletion(timeoutSeconds + 10L);
                if (!"complete".equals(completion.status())) {
                    var failedCall = provider.calls().stream().skip(callsBefore)
                            .filter(entry -> "chat".equals(entry.kind())).findFirst();
                    String failure = failedCall.map(EvaluationProvider.Call::failure).orElse("chat_failed");
                    observations.add(new TurnObservation(turn.id(), turn.expectedEligibility(), "chat_failed",
                            false, 0, 0, 0, "not_applicable", null, false, List.of(), processingMillis,
                            null, null, null, null, failure));
                    provider.releaseExtraction();
                    continue;
                }
                var submission = learning.awaitSubmission();
                boolean pendingActionApplied = false;
                if (pendingDeletion != null) {
                    try {
                        if (submission.submitted() && provider.awaitExtraction()) {
                            pendingActionApplied = applyPendingDeletion(memories, owner, fixture, pendingDeletion);
                        }
                    } finally {
                        provider.releaseExtraction();
                    }
                }
                learningWorker.submit(() -> {}).get(timeoutSeconds + 10L, TimeUnit.SECONDS);
                var call = provider.calls().stream().skip(callsBefore)
                        .filter(entry -> "extraction".equals(entry.kind())).findFirst();
                var chatCall = provider.calls().stream().skip(callsBefore)
                        .filter(entry -> "chat".equals(entry.kind())).findFirst();
                String eligibility = eligibility(turn.content().strip(), submission);
                int rawCount = 0;
                int accepted = 0;
                String validation = "not_applicable";
                String raw = null;
                Long elapsed = null;
                String failure = null;
                var candidates = new ArrayList<CandidateObservation>();
                List<MemoryCandidateParser.Candidate> acceptedItems = List.of();
                if (call.isPresent() && call.get().failure() != null) {
                    failure = call.get().failure();
                    validation = "provider_failure";
                } else if (call.isPresent()) {
                    raw = call.get().output();
                    elapsed = TimeUnit.NANOSECONDS.toMillis(call.get().durationNanos());
                    try {
                        String parseable = unwrapFence(raw);
                        var root = json.readTree(parseable);
                        rawCount = root != null && root.path("memories").isArray() ? root.path("memories").size() : 0;
                        acceptedItems = MemoryCandidateParser.parse(raw, turn.content().strip(), json);
                        accepted = acceptedItems.size();
                        validation = root == null || !root.path("memories").isArray() ? "invalid_output"
                                : rawCount > 2 ? "too_many_candidates"
                                : rawCount == accepted ? "accepted_or_empty" : "rejected_reason_unavailable";
                        if (root != null && root.path("memories").isArray()) {
                            int index = 0;
                            for (var item : root.path("memories")) {
                                String evidence = item.path("evidence").isTextual() ? item.path("evidence").asText() : null;
                                Boolean literal = evidence == null ? null : evidence.strip().length() >= 8
                                        && turn.content().strip().toLowerCase(Locale.ROOT)
                                        .contains(evidence.strip().toLowerCase(Locale.ROOT));
                                var singleton = json.createObjectNode();
                                singleton.putArray("memories").add(item);
                                boolean passed = rawCount <= 2 && raw.length() <= 4_000
                                        && !MemoryCandidateParser.parse(singleton.toString(), turn.content().strip(), json).isEmpty();
                                String content = item.path("content").isTextual() ? item.path("content").asText() : null;
                                String kind = item.path("kind").isTextual() ? item.path("kind").asText() : null;
                                Double confidence = item.path("confidence").isNumber() ? item.path("confidence").asDouble() : null;
                                candidates.add(new CandidateObservation(content, kind, confidence, evidence, literal, passed));
                                if (passed) {
                                    String status = "fact".equals(kind) && confidence != null && confidence >= 0.88
                                            ? "active" : "suggested";
                                    extracted.add(new MemoryScorer.Actual(turn.id() + "-candidate-" + index,
                                            content.strip(), status, evidence,
                                            "fact".equals(kind) ? "conversation_fact" : "inferred"));
                                }
                                index++;
                            }
                        }
                    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                        validation = "invalid_json";
                    }
                } else if (submission.submitted()) {
                    validation = "provider_result_unavailable";
                }
                int stored = (int) memories.list(owner).stream().filter(memory -> !before.contains(memory.id())).count();
                observations.add(new TurnObservation(turn.id(), turn.expectedEligibility(), eligibility,
                        call.map(EvaluationProvider.Call::providerInvoked).orElse(false), rawCount, accepted,
                        stored, validation, raw, call.map(EvaluationProvider.Call::outputTruncated).orElse(false),
                        List.copyOf(candidates), processingMillis,
                        chatCall.map(EvaluationProvider.Call::firstDeltaNanos)
                                .map(value -> TimeUnit.NANOSECONDS.toMillis(value)).orElse(null),
                        chatCall.map(value -> TimeUnit.NANOSECONDS.toMillis(value.durationNanos())).orElse(null),
                        TimeUnit.NANOSECONDS.toMillis(Math.max(0, submission.scheduledNanos() - completion.finishedNanos())),
                        elapsed, failure));
                if (pendingDeletion != null) {
                    String outcome = !pendingActionApplied || accepted == 0 ? "not_exercised"
                            : stored > 0 ? "failed"
                            : confirmCurrentRevisionCanWrite(memories, owner, conversation, acceptedItems.getFirst())
                            ? "passed" : "not_exercised";
                    lifecycle.add(new LifecycleObservation(pendingDeletion.operation(), pendingDeletion.memoryId(), outcome));
                }
                for (var action : actions) {
                    if (!"delete_while_pending".equals(action.operation())) {
                        lifecycle.add(applyAction(memories, owner, otherOwner, fixture, action, isolation));
                    }
                }
            }
            provider.followUp(true);
            int retrievalIndex = 0;
            for (var check : fixture.retrievalChecks()) {
                long started = System.nanoTime();
                String context = memories.forChat(owner, check.query());
                long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                var available = memories.list(owner);
                var beforeFollowUp = available.stream().map(PersonalMemory::id).collect(java.util.stream.Collectors.toSet());
                List<String> selected = available.stream().filter(memory -> context.contains("- "
                        + memory.content().replaceAll("\\s+", " ").strip() + " (source: "))
                        .map(memory -> memory.id().toString()).toList();
                UUID followConversation = UUID.randomUUID();
                conversations.create(followConversation, owner, java.time.Instant.now());
                int beforeCalls = provider.calls().size();
                String failure = null;
                String answer = null;
                boolean contextAsUser = false;
                boolean memoryInSystem = false;
                boolean answerTruncated = false;
                var request = new ChatStreamController.MessageRequest(UUID.randomUUID(), "gemini", model, check.query(), false);
                if (!validators.getValidator().validate(request).isEmpty()) {
                    failure = "follow_up_rejected";
                } else {
                    chat.start(followConversation, owner, request, apiKey);
                    var completion = store.awaitCompletion(timeoutSeconds + 10L);
                    if ("complete".equals(completion.status())) {
                        learning.awaitSubmission();
                        learningWorker.submit(() -> {}).get(timeoutSeconds + 10L, TimeUnit.SECONDS);
                    } else {
                        failure = "follow_up_chat_failed";
                    }
                    var followCall = provider.calls().stream().skip(beforeCalls)
                            .filter(entry -> "follow_up".equals(entry.kind())).findFirst();
                    if (followCall.isPresent()) {
                        var call = followCall.get();
                        answer = call.output();
                        answerTruncated = call.outputTruncated();
                        if (call.failure() != null) failure = call.failure();
                        if (call.providerInvoked()) {
                            contextAsUser = context.isBlank() || call.input().messages().stream().anyMatch(message ->
                                    message.role() == ChatProvider.Role.USER && context.equals(message.text()));
                            memoryInSystem = available.stream().anyMatch(memory -> call.input().systemInstruction().contains(memory.content()));
                        }
                    }
                    var extractionFailure = provider.calls().stream().skip(beforeCalls)
                            .filter(entry -> "extraction".equals(entry.kind()) && entry.failure() != null).findFirst();
                    if (extractionFailure.isPresent()) failure = "follow_up_extraction_" + extractionFailure.get().failure();
                }
                var followUpWrites = memories.list(owner).stream().filter(memory -> !beforeFollowUp.contains(memory.id()))
                        .map(MemoryEvaluationRunner::storedMemory).toList();
                retrievals.add(new RetrievalObservation("retrieval-" + (++retrievalIndex), check.query(), selected,
                        check.expectedMemoryIds(), duration, answer, contextAsUser, memoryInSystem,
                        followUpWrites, answerTruncated, failure));
            }
            actual = memories.list(owner);
        }
        var stored = actual.stream().map(MemoryEvaluationRunner::storedMemory).toList();
        var score = new MemoryScorer().score(fixture, scoringMemories(stored, observations));
        var extractionScore = new MemoryScorer().score(extractionFixture(fixture), extracted);
        var limitations = new ArrayList<String>();
        if (liveProvider == null) limitations.add("Scripted output is not a model-quality measurement.");
        if ("offline-scripted".equals(mode)) limitations.add("The offline store does not verify JDBC or database transactions.");
        limitations.add("Candidate rejection and skipped-write reasons are not exposed by the application.");
        limitations.add("Token usage and first visible browser text are not measured.");
        limitations.add("Follow-up answers require independent or human grading.");
        return new Result(fixture.id(), mode, observations, stored, List.copyOf(retrievals), lifecycle,
                extractionScore, score, List.copyOf(limitations));
    }

    private static boolean confirmCurrentRevisionCanWrite(MemoryService memories, String owner, UUID conversation,
            MemoryCandidateParser.Candidate candidate) {
        var before = memories.list(owner).stream().map(PersonalMemory::id).collect(java.util.stream.Collectors.toSet());
        memories.learn(owner, conversation, memories.revision(owner), candidate.content(), candidate.source(), candidate.confidence());
        var control = memories.list(owner).stream().filter(memory -> !before.contains(memory.id())
                && memory.content().equals(candidate.content())).findFirst();
        if (control.isEmpty()) return false;
        memories.delete(owner, control.get().id());
        return true;
    }

    private static StoredMemory storedMemory(PersonalMemory memory) {
        return new StoredMemory(memory.id().toString(), memory.content(), memory.source(), memory.status(),
                memory.confidence(), memory.sourceConversationId() == null ? null : memory.sourceConversationId().toString());
    }

    static List<MemoryScorer.Actual> scoringMemories(List<StoredMemory> memories, List<TurnObservation> turns) {
        return memories.stream().map(memory -> {
            String evidence = turns.stream().flatMap(turn -> turn.candidates().stream())
                    .filter(candidate -> candidate.accepted() && memory.content().equals(candidate.content()))
                    .map(CandidateObservation::evidence).filter(java.util.Objects::nonNull).findFirst().orElse(null);
            return new MemoryScorer.Actual(memory.id(), memory.content(), memory.status(), evidence, memory.source());
        }).toList();
    }

    private static EvaluationFixture extractionFixture(EvaluationFixture fixture) {
        var expected = fixture.expectedMemories().stream().filter(fact -> !"manual_edit".equals(fact.origin()))
                .map(fact -> "absent".equals(fact.expectedStatus())
                || ("inference".equals(fact.kind()) && "active".equals(fact.expectedStatus()))
                ? new EvaluationFixture.ExpectedMemory(fact.id(), fact.statement(), fact.aliases(), fact.sourceTurnIds(),
                        fact.kind(), fact.importance(), "inference".equals(fact.kind()) ? "suggested" : "active", fact.inScope()) : fact).toList();
        return new EvaluationFixture(fixture.id(), fixture.version(), fixture.description(), fixture.category(),
                fixture.difficulty(), fixture.importance(), fixture.inputType(), fixture.synthetic(), fixture.turns(),
                expected, fixture.forbiddenMemories(), fixture.retrievalChecks(), fixture.lifecycle(), fixture.planning());
    }

    private static String unwrapFence(String response) {
        String raw = response.strip();
        if (!raw.startsWith("```")) return raw;
        int firstLine = raw.indexOf('\n');
        int end = raw.lastIndexOf("```");
        return firstLine >= 0 && end > firstLine ? raw.substring(firstLine + 1, end).strip() : raw;
    }

    private static boolean applyPendingDeletion(MemoryService memories, String owner, EvaluationFixture fixture,
            EvaluationFixture.LifecycleAction action) {
        if (action.memoryId() == null) {
            memories.clear(owner);
            return true;
        }
        var target = findMemory(memories, owner, fixture, action.memoryId());
        if (target.isEmpty()) return false;
        memories.delete(owner, target.get().id());
        return true;
    }

    private static LifecycleObservation applyAction(MemoryService memories, String owner, String other,
            EvaluationFixture fixture, EvaluationFixture.LifecycleAction action, IsolationCheck isolation) throws Exception {
        if ("clear".equals(action.operation())) {
            memories.clear(owner);
            return new LifecycleObservation(action.operation(), action.memoryId(),
                    memories.list(owner).isEmpty() ? "passed" : "failed");
        }
        if ("other_owner".equals(action.operation())) {
            var target = action.memoryId() == null ? memories.list(owner).stream().findFirst()
                    : findMemory(memories, owner, fixture, action.memoryId());
            if (target.isEmpty()) return new LifecycleObservation(action.operation(), action.memoryId(), "not_exercised");
            boolean isolated = memories.list(other).isEmpty() && memories.forChat(other, "what do you remember").isEmpty()
                    && isolation.test(target.get().id());
            return new LifecycleObservation(action.operation(), action.memoryId(), isolated ? "passed" : "failed");
        }
        var target = findMemory(memories, owner, fixture, action.memoryId());
        if (target.isEmpty()) return new LifecycleObservation(action.operation(), action.memoryId(), "not_exercised");
        var memory = target.get();
        boolean passed = switch (action.operation()) {
            case "approve" -> "active".equals(memories.approve(owner, memory.id()).status());
            case "edit" -> memories.replace(owner, memory.id(), action.content()).content().equals(action.content());
            case "delete" -> {
                memories.delete(owner, memory.id());
                yield memories.list(owner).stream().noneMatch(item -> item.id().equals(memory.id()));
            }
            default -> throw new IllegalArgumentException("Unsupported lifecycle action");
        };
        return new LifecycleObservation(action.operation(), action.memoryId(), passed ? "passed" : "failed");
    }

    private static java.util.Optional<PersonalMemory> findMemory(MemoryService memories, String owner,
            EvaluationFixture fixture, String id) {
        return fixture.expectedMemories().stream().filter(fact -> fact.id().equals(id)).findFirst()
                .flatMap(fact -> memories.list(owner).stream().filter(memory ->
                        normalize(memory.content()).equals(normalize(fact.statement()))
                        || fact.aliases().stream().anyMatch(alias -> normalize(alias).equals(normalize(memory.content()))))
                        .findFirst());
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private static String eligibility(String text, EvaluationLearningService.Submission submission) {
        if (submission.submitted()) return "eligible";
        if (submission.rejected()) return "queue_full";
        if (!submission.revisionAvailable()) return "revision_unavailable";
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
