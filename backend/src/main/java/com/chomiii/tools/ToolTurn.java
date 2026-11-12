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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Per-turn limits shared by internal tools. Arguments and results are untrusted. */
public final class ToolTurn implements ChatProvider.Tools {
    private static final int MAX_CALLS = 2;
    private static final int MAX_RESULT_BYTES = 3_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), Thread.ofPlatform().daemon().name("internal-tool-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());

    private final ToolRegistry registry;
    private final boolean bufferText;
    private long remainingNanos = TimeUnit.SECONDS.toNanos(4);
    private final Set<String> searches = new HashSet<>();
    private int calls;

    public ToolTurn(List<InternalTool> tools, boolean bufferText) {
        this.registry = new ToolRegistry(tools);
        this.bufferText = bufferText;
    }

    @Override
    public List<ToolDefinition> definitions() {
        return registry.definitions().stream()
                .filter(definition -> definition.effect() == ToolDefinition.Effect.READ)
                .toList();
    }

    @Override
    public boolean bufferTextUntilFinal() {
        return bufferText;
    }

    @Override
    public synchronized Map<String, Object> invoke(String name, Map<String, Object> arguments) {
        if (++calls > MAX_CALLS) return ToolResponse.error(ToolResponse.ErrorCode.CALL_LIMIT, "Tool call limit reached.");
        ToolRegistry.Registered registered = registry.find(name).orElse(null);
        if (registered == null || registered.definition().effect() != ToolDefinition.Effect.READ
                || arguments == null) return ToolResponse.error(ToolResponse.ErrorCode.NOT_AVAILABLE, "Invalid tool call.");
        InternalTool tool = registered.tool();
        if (remainingNanos <= 0) return ToolResponse.error(ToolResponse.ErrorCode.TIMEOUT, "Tool time limit reached.");
        long started = System.nanoTime();
        try {
            if (JSON.writeValueAsBytes(arguments).length > 4_000) {
                return ToolResponse.error(ToolResponse.ErrorCode.INVALID_ARGUMENTS, "Invalid tool arguments.");
            }
            ToolSchemaValidator.validate(registered.definition().parameters(), arguments);
            if (!searches.add(name + "|" + tool.fingerprint(arguments))) {
                return ToolResponse.error(ToolResponse.ErrorCode.DUPLICATE_CALL, "Repeated tool call.");
            }
            Future<InternalTool.Result> future = EXECUTOR.submit(() -> tool.execute(arguments));
            InternalTool.Result result;
            try {
                result = future.get(Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(1_500)), TimeUnit.NANOSECONDS);
            } catch (TimeoutException failure) {
                future.cancel(true);
                return ToolResponse.error(ToolResponse.ErrorCode.TIMEOUT, "Tool timed out.");
            } catch (InterruptedException failure) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                return ToolResponse.error(ToolResponse.ErrorCode.CANCELLED, "Tool call cancelled.");
            } catch (ExecutionException failure) {
                return ToolResponse.error(failure.getCause() instanceof IllegalArgumentException
                                ? ToolResponse.ErrorCode.INVALID_ARGUMENTS : ToolResponse.ErrorCode.FAILURE,
                        "Tool unavailable or invalid arguments.");
            }
            if (JSON.writeValueAsBytes(result.output()).length > MAX_RESULT_BYTES) {
                return ToolResponse.error(ToolResponse.ErrorCode.RESULT_TOO_LARGE, "Tool result exceeds the context budget.");
            }
            result.onDelivered().run();
            return ToolResponse.success(result.output());
        } catch (IllegalArgumentException failure) {
            return ToolResponse.error(ToolResponse.ErrorCode.INVALID_ARGUMENTS, "Invalid tool arguments.");
        } catch (RejectedExecutionException failure) {
            return ToolResponse.error(ToolResponse.ErrorCode.NOT_AVAILABLE, "Tool unavailable.");
        } catch (RuntimeException | JsonProcessingException failure) {
            return ToolResponse.error(ToolResponse.ErrorCode.FAILURE, "Tool unavailable or invalid arguments.");
        } finally {
            remainingNanos -= System.nanoTime() - started;
        }
    }

}
