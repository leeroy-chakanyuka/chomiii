package com.chomiii.tools;

import com.chomiii.provider.ChatProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Per-turn limits shared by internal tools. Arguments and results are untrusted. */
public final class ToolTurn implements ChatProvider.Tools {
    private static final int MAX_CALLS = 2;
    private static final int MAX_RESULT_CHARS = 3_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), Thread.ofPlatform().daemon().name("internal-tool-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());

    private final List<InternalTool> tools;
    private final boolean bufferText;
    private long remainingNanos = TimeUnit.SECONDS.toNanos(4);
    private final Set<String> searches = new HashSet<>();
    private int calls;

    public ToolTurn(List<InternalTool> tools, boolean bufferText) {
        this.tools = List.copyOf(tools);
        this.bufferText = bufferText;
    }

    @Override
    public List<ChatProvider.ToolSpec> definitions() {
        return tools.stream().map(InternalTool::definition).toList();
    }

    @Override
    public boolean bufferTextUntilFinal() {
        return bufferText;
    }

    @Override
    public synchronized Map<String, Object> invoke(String name, Map<String, Object> arguments) {
        if (++calls > MAX_CALLS) return error("Tool call limit reached.");
        InternalTool tool = tools.stream().filter(candidate -> candidate.definition().name().equals(name))
                .findFirst().orElse(null);
        if (tool == null || arguments == null || arguments.size() > 3) return error("Invalid tool call.");
        if (remainingNanos <= 0) return error("Tool time limit reached.");
        long started = System.nanoTime();
        try {
            if (!searches.add(name + "|" + tool.fingerprint(arguments))) return error("Repeated tool call.");
            Future<InternalTool.Result> future = EXECUTOR.submit(() -> tool.execute(arguments));
            InternalTool.Result result;
            try {
                result = future.get(Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(1_500)), TimeUnit.NANOSECONDS);
            } catch (Exception failure) {
                future.cancel(true);
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                return error("Tool unavailable or invalid arguments.");
            }
            if (JSON.writeValueAsString(result.output()).length() > MAX_RESULT_CHARS) {
                return error("Tool result exceeds the context budget.");
            }
            result.onDelivered().run();
            return Map.of("output", result.output());
        } catch (RuntimeException | JsonProcessingException failure) {
            return error("Tool unavailable or invalid arguments.");
        } finally {
            remainingNanos -= System.nanoTime() - started;
        }
    }

    private static Map<String, Object> error(String message) {
        return Map.of("error", message);
    }
}
