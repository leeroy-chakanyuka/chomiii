package com.chomiii.session;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class RequestRateLimiter {
    private final int limit;
    private final int maxClients;
    private final Map<String, Window> windows = new LinkedHashMap<>(16, 0.75f, true);

    RequestRateLimiter(int limit, int maxClients) {
        this.limit = limit;
        this.maxClients = maxClients;
    }

    synchronized boolean allow(String client, Instant now) {
        Window current = windows.get(client);
        if (current == null) {
            if (windows.size() >= maxClients) {
                windows.values().removeIf(window -> expired(window, now));
                if (windows.size() >= maxClients) {
                    return false;
                }
            }
            windows.put(client, new Window(now, 1));
            return true;
        }
        if (expired(current, now)) {
            windows.put(client, new Window(now, 1));
            return true;
        }
        if (current.count() >= limit) {
            return false;
        }
        windows.put(client, new Window(current.start(), current.count() + 1));
        return true;
    }

    private static boolean expired(Window window, Instant now) {
        return now.isBefore(window.start()) || !now.isBefore(window.start().plusSeconds(60));
    }

    private record Window(Instant start, int count) {}
}
