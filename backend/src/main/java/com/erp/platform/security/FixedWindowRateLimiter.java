package com.erp.platform.security;

import java.time.Clock;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory per-instance request budgets in one-minute windows (SECURITY.md §9). Cluster-wide
 * protection of the authentication endpoints is database-backed (Auth); this limiter protects each
 * instance from runaway clients. Memory is bounded by evicting idle keys.
 */
public final class FixedWindowRateLimiter {

    /** Outcome of one request against its budget. */
    public record Decision(boolean allowed, int limit, int remaining, long secondsUntilReset) {}

    private static final int MAX_KEYS = 100_000;

    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public Decision tryAcquire(String key, int limitPerMinute) {
        long nowMillis = clock.millis();
        long minute = nowMillis / 60_000;
        if (windows.size() > MAX_KEYS) {
            evictIdle(minute);
        }
        Window window = windows.computeIfAbsent(key, k -> new Window());
        int used;
        synchronized (window) {
            if (window.minute != minute) {
                window.minute = minute;
                window.count = 0;
            }
            used = ++window.count;
        }
        long reset = 60 - (nowMillis / 1000) % 60;
        return new Decision(used <= limitPerMinute, limitPerMinute, Math.max(0, limitPerMinute - used), reset);
    }

    private void evictIdle(long currentMinute) {
        for (Iterator<Window> it = windows.values().iterator(); it.hasNext(); ) {
            if (it.next().minute < currentMinute) {
                it.remove();
            }
        }
    }

    private static final class Window {
        private long minute = -1;
        private int count;
    }
}
