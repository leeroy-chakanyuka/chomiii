package com.chomiii.tools;

import java.util.concurrent.TimeUnit;

/** Shared by every tool exposed in one provider turn, including local planning. */
public final class ToolCallBudget {
    private int calls;
    private long remainingNanos = TimeUnit.SECONDS.toNanos(4);

    public synchronized ToolResponse.ErrorCode claim() {
        if (++calls > 2) return ToolResponse.ErrorCode.CALL_LIMIT;
        return remainingNanos <= 0 ? ToolResponse.ErrorCode.TIMEOUT : null;
    }

    public synchronized long remainingNanos() { return remainingNanos; }

    public synchronized void charge(long nanos) { remainingNanos -= nanos; }
}
