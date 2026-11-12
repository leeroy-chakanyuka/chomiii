package com.chomiii.evaluation;

import com.chomiii.provider.ChatProvider;
import com.chomiii.provider.ProviderFailure;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Records the provider boundary; offline runs use scripted responses. */
final class EvaluationProvider implements ChatProvider {
    static final class TimedOut extends IllegalStateException {
        TimedOut() { super("Evaluation provider deadline exceeded"); }
    }
    record Call(String kind, String text, ChatInput input, String output, long durationNanos,
            Long firstDeltaNanos, boolean providerInvoked, boolean outputTruncated, String failure,
            TokenUsage usage) {}
    private final Map<String, String> extractions;
    private final Map<String, String> answers;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final ChatProvider delegate;
    private final LiveBudget budget;
    private final ScheduledExecutorService timer;
    private final long timeoutSeconds;
    private volatile String blockedText;
    private volatile CountDownLatch entered;
    private volatile CountDownLatch release;
    private volatile boolean followUp;

    EvaluationProvider(Map<String, String> extractions, Map<String, String> answers) {
        this(extractions, answers, null, null, null, 0);
    }

    EvaluationProvider(Map<String, String> extractions, Map<String, String> answers,
            ChatProvider delegate, LiveBudget budget, ScheduledExecutorService timer, long timeoutSeconds) {
        this.extractions = Map.copyOf(extractions);
        this.answers = Map.copyOf(answers);
        this.delegate = delegate;
        this.budget = budget;
        this.timer = timer;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override public String id() { return "gemini"; }

    @Override public Generation open(ChatInput input, String apiKey) {
        return open(input, apiKey, null);
    }

    @Override public Generation open(ChatInput input, String apiKey, Tools tools) {
        boolean extraction = input.systemInstruction().contains("memory-extraction classifier");
        boolean planning = input.systemInstruction().contains("assistant preparing a proposal");
        String text = input.messages().getLast().text();
        String output = extraction ? extractions.getOrDefault(text, "{\"memories\":[]}")
                : answers.getOrDefault(text, "Understood.");
        String kind = extraction ? "extraction" : planning ? "planning" : followUp ? "follow_up" : "chat";
        Generation upstream = null;
        if (delegate != null) {
            try {
                budget.reserve(extraction ? LiveBudget.Kind.EXTRACTION : planning ? LiveBudget.Kind.PLANNING
                        : followUp ? LiveBudget.Kind.FOLLOW_UP : LiveBudget.Kind.CHAT,
                        input.systemInstruction().getBytes(StandardCharsets.UTF_8).length
                                + input.messages().stream().mapToLong(message -> message.text().getBytes(StandardCharsets.UTF_8).length).sum());
            } catch (RuntimeException failure) {
                calls.add(new Call(kind, text, input, null, 0, null, false, false, failure.getClass().getSimpleName(), null));
                throw failure;
            }
            try {
                Tools bounded = tools == null ? null : new Tools() {
                    @Override public List<ToolSpec> definitions() { return tools.definitions(); }
                    @Override public Map<String, Object> invoke(String name, Map<String, Object> arguments) {
                        return tools.invoke(name, arguments);
                    }
                    @Override public boolean bufferTextUntilFinal() { return tools.bufferTextUntilFinal(); }
                    @Override public void beforeContinuation(long inputBytes) {
                        budget.reserve(LiveBudget.Kind.TOOL_CONTINUATION, inputBytes);
                        tools.beforeContinuation(inputBytes);
                    }
                };
                upstream = bounded == null ? delegate.open(input, apiKey) : delegate.open(input, apiKey, bounded);
            } catch (RuntimeException failure) {
                calls.add(new Call(kind, text, input, null, 0, null, true, false, failureName(failure), null));
                throw failure;
            }
        }
        Generation source = upstream;
        return new Generation() {
            @Override public void stream(Consumer<String> delta) {
                long start = System.nanoTime();
                StringBuilder received = new StringBuilder();
                long[] first = {0};
                boolean[] truncated = {false};
                int captureLimit = extraction ? 4_001 : planning ? 20_001 : 16_001;
                String failure = null;
                var timedOut = new AtomicBoolean();
                ScheduledFuture<?> watchdog = source == null ? null
                        : timer.schedule(() -> { timedOut.set(true); source.close(); }, timeoutSeconds, TimeUnit.SECONDS);
                try {
                    if (extraction && text.equals(blockedText)) {
                        entered.countDown();
                        try {
                            if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Extraction gate timed out");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Extraction interrupted", interrupted);
                        }
                    }
                    Consumer<String> record = chunk -> {
                        if (first[0] == 0) first[0] = System.nanoTime() - start;
                        int available = Math.max(0, captureLimit - received.length());
                        received.append(chunk, 0, Math.min(available, chunk.length()));
                        if (chunk.length() > available) truncated[0] = true;
                        delta.accept(chunk);
                    };
                    if (source == null) record.accept(output);
                    else source.stream(record);
                    if (source != null && (timedOut.get()
                            || System.nanoTime() - start >= TimeUnit.SECONDS.toNanos(timeoutSeconds))) throw new TimedOut();
                } catch (RuntimeException error) {
                    failure = failureName(error);
                    throw error;
                } finally {
                    if (watchdog != null) watchdog.cancel(false);
                    calls.add(new Call(kind, text, input, received.toString(), System.nanoTime() - start,
                            first[0] == 0 ? null : first[0], true, truncated[0], failure,
                            source == null ? null : source.usage().orElse(null)));
                }
            }
            @Override public void close() { if (source != null) source.close(); }
        };
    }

    List<Call> calls() { return List.copyOf(calls); }

    private String failureName(RuntimeException failure) {
        if (failure instanceof ProviderFailure providerFailure) {
            String code = providerFailure.code().name();
            if (budget != null) budget.stopForProviderFailure(code);
            return code;
        }
        return failure.getClass().getSimpleName();
    }

    void followUp(boolean enabled) { followUp = enabled; }

    void blockExtraction(String text) {
        blockedText = text;
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
    }

    boolean awaitExtraction() throws InterruptedException { return entered.await(10, TimeUnit.SECONDS); }
    void releaseExtraction() { if (release != null) release.countDown(); }
}
