package com.chomiii.tools;

import com.chomiii.provider.ChatProvider;
import com.chomiii.approval.ApprovalFacade;
import com.chomiii.tools.trace.ToolTraceCommand;
import com.chomiii.tools.trace.ToolTraceDto;
import com.chomiii.tools.trace.ToolTraceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Per-turn limits shared by internal tools. Arguments and results are untrusted. */
public final class ToolTurn implements ChatProvider.Tools {
    private static final int MAX_CALLS = 2;
    private static final int MAX_RESULT_BYTES = 3_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), Thread.ofPlatform().daemon().name("internal-tool-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());

    private final ToolRegistry registry;
    private final ToolExecutionContext context;
    private final boolean bufferText;
    private final ToolTraceService traces;
    private final String provider;
    private final String model;
    private final Consumer<ToolTraceDto.Trace> traceObserver;
    private final ApprovalFacade approvals;
    private final Consumer<Object> approvalObserver;
    private final AtomicBoolean proposed = new AtomicBoolean();
    private long remainingNanos = TimeUnit.SECONDS.toNanos(4);
    private final Set<String> searches = new HashSet<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Future<?>> active = new AtomicReference<>();
    private int calls;

    public ToolTurn(ToolRegistry registry, ToolExecutionContext context, boolean bufferText) {
        this(registry, context, bufferText, null, null, null, ignored -> {});
    }

    public ToolTurn(ToolRegistry registry, ToolExecutionContext context, boolean bufferText,
            ToolTraceService traces, String provider, String model, Consumer<ToolTraceDto.Trace> traceObserver) {
        this(registry, context, bufferText, traces, provider, model, traceObserver, null, ignored -> {});
    }

    public ToolTurn(ToolRegistry registry, ToolExecutionContext context, boolean bufferText,
            ToolTraceService traces, String provider, String model, Consumer<ToolTraceDto.Trace> traceObserver,
            ApprovalFacade approvals, Consumer<Object> approvalObserver) {
        this.registry = registry;
        this.context = context;
        this.bufferText = bufferText;
        this.traces = traces;
        this.provider = provider;
        this.model = model;
        this.traceObserver = traceObserver;
        this.approvals = approvals;
        this.approvalObserver = approvalObserver;
    }

    public boolean hasProposal() { return proposed.get(); }

    @Override
    public List<ToolDefinition> definitions() {
        return registry.available(context);
    }

    @Override
    public boolean bufferTextUntilFinal() {
        return bufferText;
    }

    @Override
    public synchronized Map<String, Object> invoke(String name, Map<String, Object> arguments) {
        ToolRegistry.Registered registered = name == null ? null : registry.find(name).orElse(null);
        CallTrace call = null;
        if (traces != null) {
            try {
                var definition = registered == null ? null : registered.definition();
                var requested = traces.recordRequested(context.owner(), new ToolTraceCommand.Requested(
                        context.conversationId(), context.requestId(), null, null,
                        definition == null ? "unknown" : definition.name(),
                        definition == null ? "unknown" : definition.capability(),
                        definition == null ? null : definition.effect(), provider, model, null, null, arguments));
                call = new CallTrace(requested);
                publish(requested);
            } catch (RuntimeException failure) {
                return ToolResponse.error(ToolResponse.ErrorCode.NOT_AVAILABLE, "Tool trace unavailable.");
            }
        }
        Map<String, Object> response;
        try {
            response = invokeRegistered(name, arguments, registered, call);
        } catch (Error failure) {
            if (call != null) {
                try { publish(finish(call, ToolResponse.error(ToolResponse.ErrorCode.FAILURE, "Tool failed."))); }
                catch (RuntimeException ignored) { }
            }
            throw failure;
        }
        if (call != null) {
            try {
                if (!"APPROVAL_REQUIRED".equals(response.get("errorCode"))) publish(finish(call, response));
            } catch (RuntimeException failure) {
                return ToolResponse.error(ToolResponse.ErrorCode.FAILURE, "Tool trace unavailable.");
            }
        }
        return response;
    }

    private Map<String, Object> invokeRegistered(String name, Map<String, Object> arguments,
            ToolRegistry.Registered registered, CallTrace call) {
        if (cancelled.get()) return ToolResponse.error(ToolResponse.ErrorCode.CANCELLED, "Tool call cancelled.");
        if (++calls > MAX_CALLS) return ToolResponse.error(ToolResponse.ErrorCode.CALL_LIMIT, "Tool call limit reached.");
        if (registered == null || arguments == null) {
            return ToolResponse.error(ToolResponse.ErrorCode.NOT_AVAILABLE, "Invalid tool call.");
        }
        if (!registry.mayExpose(registered, context)) {
            return ToolResponse.error(ToolResponse.ErrorCode.NOT_AUTHORIZED, "Tool is not authorized.");
        }
        if (registry.findAvailable(name, context).isEmpty()) {
            return ToolResponse.error(ToolResponse.ErrorCode.NOT_AVAILABLE, "Invalid tool call.");
        }
        InternalTool tool = registered.tool();
        if (remainingNanos <= 0) return ToolResponse.error(ToolResponse.ErrorCode.TIMEOUT, "Tool time limit reached.");
        long started = System.nanoTime();
        try {
            Map<String, Object> safeArguments = ToolValues.copy(arguments);
            if (JSON.writeValueAsBytes(safeArguments).length > 4_000) {
                return ToolResponse.error(ToolResponse.ErrorCode.INVALID_ARGUMENTS, "Invalid tool arguments.");
            }
            ToolSchemaValidator.validate(registered.definition().parameters(), safeArguments);
            var decision = registry.decision(registered, context, safeArguments);
            if (decision != ToolAuthorization.Decision.ALLOW && decision != ToolAuthorization.Decision.REQUIRE_APPROVAL) {
                return ToolResponse.error(ToolResponse.ErrorCode.NOT_AUTHORIZED, "Tool is not authorized.");
            }
            if (decision == ToolAuthorization.Decision.REQUIRE_APPROVAL) {
                if (approvals == null || traces == null || call == null) {
                    return ToolResponse.error(ToolResponse.ErrorCode.NOT_AVAILABLE, "Approval unavailable.");
                }
                var approval = approvals.propose(context, registered.definition(), safeArguments, call.id);
                proposed.set(true);
                try {
                if (cancelled.get()) {
                    approvals.cancelRequest(context.owner(), context.conversationId(), context.requestId());
                    return ToolResponse.error(ToolResponse.ErrorCode.CANCELLED, "Tool call cancelled.");
                }
                traces.list(context.conversationId(), context.owner()).stream()
                        .filter(trace -> trace.invocationId().equals(call.id)).findFirst().ifPresent(this::publish);
                if (cancelled.get()) {
                    approvals.cancelRequest(context.owner(), context.conversationId(), context.requestId());
                    return ToolResponse.error(ToolResponse.ErrorCode.CANCELLED, "Tool call cancelled.");
                }
                approvalObserver.accept(approval);
                return ToolResponse.error(ToolResponse.ErrorCode.APPROVAL_REQUIRED, "Explicit approval is required; the action has not been performed.");
                } catch (RuntimeException failure) {
                    approvals.cancelRequest(context.owner(), context.conversationId(), context.requestId());
                    throw failure;
                }
            }
            if (registered.definition().effect() == ToolDefinition.Effect.WRITE) {
                return ToolResponse.error(ToolResponse.ErrorCode.NOT_AUTHORIZED, "Writes cannot execute in a generation.");
            }
            if (call != null) traces.recordAuthorized(context.owner(), call.id, null);
            if (!searches.add(name + "|" + tool.fingerprint(safeArguments))) {
                return ToolResponse.error(ToolResponse.ErrorCode.DUPLICATE_CALL, "Repeated tool call.");
            }
            Future<InternalTool.Result> future = EXECUTOR.submit(() -> {
                if (cancelled.get()) throw new CancellationException();
                if (call != null) {
                    if (call.finished.get()) throw new CancellationException();
                    var startedAt = traces.recordStarted(context.owner(), call.id);
                    if (call.finished.get() || cancelled.get()) throw new CancellationException();
                    publish(call.requested.started(startedAt));
                    if (call.finished.get() || cancelled.get()) throw new CancellationException();
                }
                return tool.execute(context, safeArguments);
            });
            active.set(future);
            if (cancelled.get()) future.cancel(true);
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
                return ToolResponse.error(failure.getCause() instanceof CancellationException
                                ? ToolResponse.ErrorCode.CANCELLED : failure.getCause() instanceof IllegalArgumentException
                                ? ToolResponse.ErrorCode.INVALID_ARGUMENTS : ToolResponse.ErrorCode.FAILURE,
                        "Tool unavailable or invalid arguments.");
            } catch (CancellationException failure) {
                return ToolResponse.error(ToolResponse.ErrorCode.CANCELLED, "Tool call cancelled.");
            } finally {
                active.compareAndSet(future, null);
            }
            if (JSON.writeValueAsBytes(result.output()).length > MAX_RESULT_BYTES) {
                return ToolResponse.error(ToolResponse.ErrorCode.RESULT_TOO_LARGE, "Tool result exceeds the context budget.");
            }
            if (cancelled.get()) return ToolResponse.error(ToolResponse.ErrorCode.CANCELLED, "Tool call cancelled.");
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

    private ToolTraceDto.Trace finish(CallTrace call, Map<String, Object> response) {
        call.finished.set(true);
        Object code = response.get("errorCode");
        String error = code == null ? null : String.valueOf(code);
        String status = error == null ? "succeeded" : switch (error) {
            case "NOT_AUTHORIZED" -> "denied";
            case "CANCELLED" -> "cancelled";
            case "TIMEOUT" -> "timeout";
            default -> "failed";
        };
        return traces.recordFinished(context.owner(), call.id, status, error, response);
    }

    private void publish(ToolTraceDto.Trace trace) {
        try {
            traceObserver.accept(trace);
        } catch (RuntimeException disconnected) {
            close();
        }
    }

    private static final class CallTrace {
        private final UUID id;
        private final ToolTraceDto.Trace requested;
        private final AtomicBoolean finished = new AtomicBoolean();
        private CallTrace(ToolTraceDto.Trace requested) {
            this.requested = requested;
            this.id = requested.invocationId();
        }
    }

    @Override
    public void close() {
        cancelled.set(true);
        Future<?> future = active.getAndSet(null);
        if (future != null) future.cancel(true);
    }

}
