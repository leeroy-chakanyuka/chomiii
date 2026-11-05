package com.chomiii.evaluation;

import java.util.EnumMap;
import java.util.Map;

final class LiveBudget {
    enum Kind { CHAT, EXTRACTION, FOLLOW_UP, JUDGE }
    static final class Exhausted extends IllegalStateException {
        Exhausted() { super("Provider call budget exhausted"); }
    }
    private final boolean enabled;
    private final int limit;
    private final EnumMap<Kind, Integer> counts = new EnumMap<>(Kind.class);

    LiveBudget(boolean enabled, int limit) {
        if (limit < 0) throw new IllegalArgumentException("Budget cannot be negative");
        this.enabled = enabled;
        this.limit = limit;
    }

    synchronized void reserve(Kind kind) {
        if (!enabled) throw new IllegalStateException("Live evaluation is disabled");
        if (counts.values().stream().mapToInt(Integer::intValue).sum() >= limit) {
            throw new Exhausted();
        }
        counts.merge(kind, 1, Integer::sum);
    }

    synchronized Map<Kind, Integer> counts() { return Map.copyOf(counts); }

    synchronized boolean exhausted() {
        return counts.values().stream().mapToInt(Integer::intValue).sum() >= limit;
    }
}
