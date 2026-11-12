package com.chomiii.chat;

import com.chomiii.agent.AgentInstructions;
import com.chomiii.memory.MemoryService;
import com.chomiii.memory.MemoryLearningService;
import com.chomiii.memory.MemorySearchTool;
import com.chomiii.memory.HybridMemorySearch;
import com.chomiii.memory.embedding.EmbeddingIndexer;
import com.chomiii.memory.PersonalMemory;
import com.chomiii.planning.PlanningRouter;
import com.chomiii.planning.PlanningService;
import java.util.ArrayList;
import com.chomiii.provider.ChatProvider;
import com.chomiii.provider.ProviderCatalog;
import com.chomiii.provider.ProviderFailure;
import com.chomiii.tools.ToolTurn;
import com.chomiii.tools.ToolRegistry;
import com.chomiii.tools.ToolExecutionContext;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class ChatStreamService {
    private static final Logger log = LoggerFactory.getLogger(ChatStreamService.class);
    private static final AgentInstructions AGENT_INSTRUCTIONS = new AgentInstructions();
    private static final PlanningService PLANNING = new PlanningService();
    private static final int MAX_REPLY_CHARS = 16_000;
    private final ChatTurnStore store;
    private final ConversationRepository conversations;
    private final MemoryService memories;
    private final MemoryLearningService learning;
    private final ProviderCatalog catalog;
    private final List<ChatProvider> providers;
    private final ExecutorService executor;
    private final boolean memorySearchEnabled;
    private final EmbeddingIndexer embeddingIndexer;
    private final HybridMemorySearch hybridMemorySearch;
    private final ToolRegistry toolRegistry;
    private final Semaphore slots = new Semaphore(8);
    private final ConcurrentHashMap<UUID, Active> active = new ConcurrentHashMap<>();

    public ChatStreamService(ChatTurnStore store, ConversationRepository conversations, MemoryService memories,
            MemoryLearningService learning, ProviderCatalog catalog, List<ChatProvider> providers,
            ExecutorService chatExecutor) {
        this(store, conversations, memories, learning, catalog, providers, chatExecutor, true);
    }

    public ChatStreamService(ChatTurnStore store, ConversationRepository conversations, MemoryService memories,
            MemoryLearningService learning, ProviderCatalog catalog, List<ChatProvider> providers,
            ExecutorService chatExecutor, boolean memorySearchEnabled) {
        this(store, conversations, memories, learning, catalog, providers, chatExecutor, memorySearchEnabled, null, null,
                new ToolRegistry(List.of(new MemorySearchTool(memories, null))));
    }

    @Autowired
    public ChatStreamService(ChatTurnStore store, ConversationRepository conversations, MemoryService memories,
            MemoryLearningService learning, ProviderCatalog catalog, List<ChatProvider> providers,
            @Qualifier("chatExecutor") ExecutorService chatExecutor,
            @Value("${chomiii.memory.search-tool.enabled:true}") boolean memorySearchEnabled,
            EmbeddingIndexer embeddingIndexer, HybridMemorySearch hybridMemorySearch, ToolRegistry toolRegistry) {
        this.store = store;
        this.conversations = conversations;
        this.memories = memories;
        this.learning = learning;
        this.catalog = catalog;
        this.providers = List.copyOf(providers);
        this.executor = chatExecutor;
        this.memorySearchEnabled = memorySearchEnabled;
        this.embeddingIndexer = embeddingIndexer;
        this.hybridMemorySearch = hybridMemorySearch;
        this.toolRegistry = toolRegistry;
    }

    public SseEmitter start(UUID conversation, String owner, ChatStreamController.MessageRequest request, String key) {
        return start(conversation, owner, request, key, true);
    }

    public SseEmitter start(UUID conversation, String owner, ChatStreamController.MessageRequest request,
            String key, boolean useMemory) {
        if (key == null || key.isBlank() || key.length() > 512) {
            throw new ChatApiException(HttpStatus.BAD_REQUEST, "Enter a Gemini API key.");
        }
        ChatProvider provider = providers.stream().filter(candidate -> request.provider().equals(candidate.id()))
                .findFirst().orElse(null);
        if (!catalog.supports(request.provider(), request.model()) || provider == null) {
            throw new ChatApiException(HttpStatus.BAD_REQUEST, "Unsupported provider or model.");
        }
        if (!slots.tryAcquire()) {
            throw new ChatApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many generations are in progress.");
        }
        long requestStartedNanos = System.nanoTime();
        // The memory revision is captured BEFORE the user's turn is stored, as
        // part of the originating turn. Any deletion, correction or clear that
        // overlaps this turn (while the reply is being generated, or even between
        // this read and the turn being persisted) increments it, so the
        // background extraction scheduled later can prove whether its learning is
        // still aligned with the user's memory. If the optional memory store
        // cannot be reached, the turn proceeds and simply never learns.
        final Long memoryRevision = turnRevision(memories, owner);
        ChatTurnStore.Pending pending;
        try {
            pending = store.begin(conversation, owner, request.requestId(), request.content().strip());
        } catch (RuntimeException exception) {
            slots.release();
            throw exception;
        }
        long storeReadyNanos = System.nanoTime();
        // Retrieval is an optional, owner-scoped source of untrusted context.
        // Reserve its budget BEFORE selecting history so the full model input
        // stays within the established message and character bounds.
        boolean plan = request.plan() != null ? request.plan() : PlanningRouter.matches(request.content());
        List<PersonalMemory> planningMemories;
        ChatProvider.ChatInput input;
        try {
            planningMemories = new ArrayList<>(plan && useMemory
                    ? planningMemories(memories, owner, request.content()) : List.of());
            String memoryContext = useMemory ? retrievalContext(memories, owner, request.content()) : "";
            if (useMemory && hybridMemorySearch != null) {
                try {
                    var supplement = hybridMemorySearch.augment(owner, request.content(), key, memoryContext);
                    memoryContext = supplement.context();
                    if (plan) planningMemories.addAll(supplement.memories());
                } catch (RuntimeException unavailable) {
                    // Optional semantic retrieval cannot prevent a normal reply.
                }
            }
            if (plan) {
                input = PLANNING.input(request.model(), pending.history(), request.content().strip(),
                        planningMemories, memoryContext);
            } else {
                boolean hasMemory = !memoryContext.isBlank();
                int memoryChars = hasMemory ? memoryContext.length() : 0;
                List<ChatProvider.Message> messages = new ArrayList<>(
                        ContextWindow.select(pending.history(), request.content().strip(),
                                hasMemory ? 19 : 20, 16_000 - memoryChars));
                if (hasMemory) {
                    messages.add(messages.size() - 1,
                            new ChatProvider.Message(ChatProvider.Role.USER, memoryContext));
                }
                input = new ChatProvider.ChatInput(request.model(), AGENT_INSTRUCTIONS.systemPrompt(), List.copyOf(messages));
            }
        } catch (RuntimeException failure) {
            slots.release();
            store.finish(conversation, owner, request.requestId(), pending.assistantId(), "", "failed");
            throw new ChatApiException(HttpStatus.SERVICE_UNAVAILABLE, "Could not prepare the response. Try again.");
        }
        long contextReadyNanos = System.nanoTime();
        var emitter = new SseEmitter(130_000L);
        var generation = new Active(owner, conversation);
        active.put(request.requestId(), generation);
        emitter.onCompletion(generation::cancelUnlessFinished);
        emitter.onError(ignored -> generation.cancelUnlessFinished());
        emitter.onTimeout(generation::cancelUnlessFinished);
        try {
            executor.execute(() -> run(request.requestId(), pending.assistantId(), owner, conversation,
                    provider, input, key, generation, emitter, memoryRevision, requestStartedNanos,
                    storeReadyNanos, contextReadyNanos, plan, planningMemories, useMemory));
        } catch (RuntimeException exception) {
            active.remove(request.requestId(), generation);
            slots.release();
            store.finish(conversation, owner, request.requestId(), pending.assistantId(), "", "interrupted");
            throw new ChatApiException(HttpStatus.SERVICE_UNAVAILABLE, "Chat is unavailable.");
        }
        return emitter;
    }

    public void cancel(UUID conversation, String owner, UUID requestId) {
        if (conversations.find(conversation, owner).isEmpty()) {
            throw new ConversationNotFoundException();
        }
        Active generation = active.get(requestId);
        if (generation == null || !generation.owner.equals(owner) || !generation.conversation.equals(conversation)) {
            throw new ChatApiException(HttpStatus.CONFLICT, "This generation is no longer running.");
        }
        generation.cancelUnlessFinished();
    }

    public void cancelConversation(UUID conversation, String owner) {
        active.forEach((requestId, generation) -> {
            if (generation.conversation.equals(conversation) && generation.owner.equals(owner)) {
                generation.cancelUnlessFinished();
            }
        });
    }

    private void run(UUID requestId, UUID assistantId, String owner, UUID conversation, ChatProvider provider,
            ChatProvider.ChatInput input, String key, Active generation, SseEmitter emitter, Long memoryRevision,
            long requestStartedNanos, long storeReadyNanos, long contextReadyNanos,
            boolean planning, List<PersonalMemory> planningMemories, boolean useMemory) {
        long workerStartedNanos = System.nanoTime();
        StringBuilder response = new StringBuilder();
        long[] firstDeltaNanos = {0L};
        long[] firstSentNanos = {0L};
        long finishMs = -1;
        String outcome = "failed";
        try {
            send(emitter, "start", Map.of("requestId", requestId.toString(), "messageId", assistantId.toString()));
            var toolMemoryIds = new java.util.HashSet<UUID>();
            var toolContext = new ToolExecutionContext(owner, key, useMemory && memorySearchEnabled, observation -> {
                if ("memory.search".equals(observation.toolName())) toolMemoryIds.addAll(observation.references());
            });
            var session = new ToolTurn(toolRegistry, toolContext, planning);
            var tools = session.definitions().isEmpty() ? null : session;
            if (tools == null) session.close();
            generation.setTools(tools);
            try (ChatProvider.Generation providerStream = tools == null
                    ? provider.open(input, key) : provider.open(input, key, tools)) {
                generation.set(providerStream);
                if (generation.cancelled.get()) {
                    throw new CancellationException();
                }
                Consumer<String> delta = chunk -> {
                    if (generation.cancelled.get()) {
                        throw new CancellationException();
                    }
                    if (response.length() + chunk.length() > MAX_REPLY_CHARS) {
                        throw new ChatApiException(HttpStatus.BAD_GATEWAY, "Gemini returned too much text.");
                    }
                    if (firstDeltaNanos[0] == 0L) {
                        firstDeltaNanos[0] = System.nanoTime();
                    }
                    response.append(chunk);
                    send(emitter, "delta", Map.of("text", chunk));
                    if (firstSentNanos[0] == 0L) {
                        firstSentNanos[0] = System.nanoTime();
                    }
                };
                if (planning) {
                    var plan = PLANNING.readWithAdditionalMemoryIds(providerStream, planningMemories, toolMemoryIds);
                    if (generation.cancelled.get()) throw new CancellationException();
                    String rendered = PLANNING.render(plan);
                    if (rendered.length() > MAX_REPLY_CHARS) {
                        throw new ChatApiException(HttpStatus.BAD_GATEWAY, "The plan was too large.");
                    }
                    send(emitter, "plan", plan);
                    delta.accept(rendered);
                } else {
                    providerStream.stream(delta);
                }
            } finally {
                if (tools != null) tools.close();
            }
            if (generation.cancelled.get()) {
                throw new CancellationException();
            }
            long finishStartedNanos = System.nanoTime();
            store.finish(conversation, owner, requestId, assistantId, response.toString(), "complete");
            finishMs = elapsedMillis(finishStartedNanos, System.nanoTime());
            generation.finished.set(true);
            outcome = "complete";
            send(emitter, "complete", Map.of("messageId", assistantId.toString(), "status", "complete"));
        } catch (RuntimeException exception) {
            if (generation.finished.get()) {
                return;
            }
            boolean interrupted = generation.cancelled.get() || exception instanceof CancellationException
                    || exception instanceof ProviderFailure failure && failure.code() == ProviderFailure.Code.CANCELLED;
            outcome = interrupted ? "interrupted" : "failed";
            String code = interrupted ? "INTERRUPTED" : exception instanceof ProviderFailure failure
                    ? failure.code().name() : planning && exception instanceof IllegalArgumentException ? "INVALID_PLAN"
                    : exception instanceof ChatApiException ? "OUTPUT_LIMIT" : "UNAVAILABLE";
            String message = interrupted ? "Generation stopped." : exception instanceof ProviderFailure failure
                    ? failure.getMessage() : planning && exception instanceof IllegalArgumentException
                    ? "Could not build a valid plan. Try again." : "The response could not be completed.";
            try {
                store.finish(conversation, owner, requestId, assistantId, response.toString(),
                        interrupted ? "interrupted" : "failed");
                generation.finished.set(true);
            } catch (RuntimeException databaseFailure) {
                code = "STORAGE_UNAVAILABLE";
                message = "Could not save the response.";
            }
            try {
                send(emitter, "error", Map.of("code", code, "message", message, "status",
                        interrupted ? "interrupted" : "failed"));
            } catch (RuntimeException disconnected) {
                generation.cancelUnlessFinished();
            }
        } finally {
            long completedNanos = System.nanoTime();
            log.info("chat_latency provider={} model={} outcome={} store_ms={} context_ms={} queue_ms={} "
                            + "first_delta_ms={} first_sse_ms={} finish_ms={} total_ms={}",
                    provider.id(), input.model(), outcome,
                    elapsedMillis(requestStartedNanos, storeReadyNanos),
                    elapsedMillis(storeReadyNanos, contextReadyNanos),
                    elapsedMillis(contextReadyNanos, workerStartedNanos),
                    firstDeltaNanos[0] == 0L ? -1 : elapsedMillis(requestStartedNanos, firstDeltaNanos[0]),
                    firstSentNanos[0] == 0L ? -1 : elapsedMillis(firstDeltaNanos[0], firstSentNanos[0]),
                    finishMs, elapsedMillis(requestStartedNanos, completedNanos));
            active.remove(requestId, generation);
            slots.release();
            emitter.complete();
            if ("complete".equals(outcome)) {
                // Automatic, best-effort memory extraction is scheduled only AFTER the
                // visible reply has completed. It uses an additional provider request
                // but must not delay or change the completed chat response. It claims
                // the revision captured at turn start, so a queued extraction cannot
                // recreate content the user corrected or deleted in the meantime.
                try {
                    learning.schedule(owner, conversation, memoryRevision,
                            input.messages().getLast().text(), provider, input.model(), key);
                } catch (RuntimeException ignored) {
                    log.debug("memory_learning scheduling unavailable");
                }
                if (useMemory && embeddingIndexer != null) embeddingIndexer.schedule(owner, key);
            }
        }
    }

    /**
     * Spring's transaction proxy may fail before MemoryService can catch a
     * DataAccessException (e.g. CannotCreateTransactionException when the
     * connection pool is exhausted). Retrieval is optional: chat proceeds
     * without memory context instead of failing an otherwise valid request.
     */
    private static String retrievalContext(MemoryService memories, String owner, String question) {
        try {
            String context = memories.forChat(owner, question);
            return context == null ? "" : context;
        } catch (DataAccessException | TransactionException unavailable) {
            return "";
        }
    }

    private static List<PersonalMemory> planningMemories(MemoryService memories, String owner, String question) {
        try {
            return memories.forPlanning(owner, question);
        } catch (DataAccessException | TransactionException unavailable) {
            return List.of();
        }
    }

    /**
     * Same optional-failure handling for the turn-start revision baseline: an
     * unreadable store yields {@code null}, and such a turn never leaves a
     * pending learning claim.
     */
    private static Long turnRevision(MemoryService memories, String owner) {
        try {
            return memories.revision(owner);
        } catch (DataAccessException | TransactionException unavailable) {
            return null;
        }
    }

    private static long elapsedMillis(long startNanos, long endNanos) {
        return TimeUnit.NANOSECONDS.toMillis(endNanos - startNanos);
    }

    private static void send(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(event).data(payload));
        } catch (IOException | IllegalStateException exception) {
            throw new CancellationException();
        }
    }

    private static final class Active {
        private final String owner;
        private final UUID conversation;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicReference<ChatProvider.Generation> provider = new AtomicReference<>();
        private final AtomicReference<ChatProvider.Tools> tools = new AtomicReference<>();

        private Active(String owner, UUID conversation) {
            this.owner = owner;
            this.conversation = conversation;
        }

        private void set(ChatProvider.Generation stream) {
            provider.set(stream);
            if (cancelled.get()) {
                stream.close();
            }
        }

        private void setTools(ChatProvider.Tools session) {
            tools.set(session);
            if (session != null && cancelled.get()) session.close();
        }

        private void cancelUnlessFinished() {
            if (!finished.get() && cancelled.compareAndSet(false, true)) {
                ChatProvider.Tools session = tools.get();
                if (session != null) session.close();
                ChatProvider.Generation stream = provider.get();
                if (stream != null) {
                    stream.close();
                }
            }
        }
    }
}
