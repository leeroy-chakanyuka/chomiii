package com.chomiii.evaluation;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class LiveBudget {
    enum Kind { CHAT, EXTRACTION, FOLLOW_UP, PLANNING, TOOL_CONTINUATION, JUDGE }
    static final class Exhausted extends IllegalStateException {
        Exhausted() { super("Provider call budget exhausted"); }
    }
    private final boolean enabled;
    private final int limit;
    private final long minIntervalNanos;
    private final int maxInputBytes;
    private final EnumMap<Kind, Integer> counts = new EnumMap<>(Kind.class);
    private String providerFailure;
    private boolean hasReservation;
    private long lastReservationNanos;

    LiveBudget(boolean enabled, int limit) {
        this(enabled, limit, 0, Integer.MAX_VALUE);
    }

    LiveBudget(boolean enabled, int limit, int minIntervalMillis) {
        this(enabled, limit, minIntervalMillis, Integer.MAX_VALUE);
    }

    LiveBudget(boolean enabled, int limit, int minIntervalMillis, int maxInputBytes) {
        if (limit < 0 || minIntervalMillis < 0 || maxInputBytes < 1) {
            throw new IllegalArgumentException("Invalid provider budget");
        }
        this.enabled = enabled;
        this.limit = limit;
        this.minIntervalNanos = TimeUnit.MILLISECONDS.toNanos(minIntervalMillis);
        this.maxInputBytes = maxInputBytes;
    }

    synchronized void reserve(Kind kind) {
        reserve(kind, 0);
    }

    synchronized void reserve(Kind kind, long inputBytes) {
        if (!enabled) throw new IllegalStateException("Live evaluation is disabled");
        if (inputBytes > maxInputBytes) throw new IllegalStateException("Provider input exceeds the evaluation byte limit");
        while (true) {
            if (providerFailure != null) throw new IllegalStateException("Provider "
                    + (rateLimited() ? "rate limit reached" : "failed") + "; evaluation stopped");
            if (counts.values().stream().mapToInt(Integer::intValue).sum() >= limit) throw new Exhausted();
            long now = System.nanoTime();
            long waitNanos = hasReservation ? minIntervalNanos - (now - lastReservationNanos) : 0;
            if (waitNanos <= 0) {
                counts.merge(kind, 1, Integer::sum);
                hasReservation = true;
                lastReservationNanos = now;
                return;
            }
            try {
                TimeUnit.NANOSECONDS.timedWait(this, waitNanos);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Provider pacing interrupted", interrupted);
            }
        }
    }

    synchronized Map<Kind, Integer> counts() { return Map.copyOf(counts); }

    synchronized void stopForProviderFailure(String code) {
        if (providerFailure == null) providerFailure = code;
        notifyAll();
    }

    synchronized boolean rateLimited() { return "RATE_LIMITED".equals(providerFailure); }

    synchronized boolean providerFailed() { return providerFailure != null; }

    synchronized boolean exhausted() {
        return providerFailure != null || counts.values().stream().mapToInt(Integer::intValue).sum() >= limit;
    }
}
