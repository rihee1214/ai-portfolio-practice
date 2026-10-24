package com.example.marketplace.identity.web;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/** Bounded single-process fixed window. No raw credentials are retained. */
public final class AttemptWindow {
    private record Window(long until, int count) { }
    private final Map<String, Window> windows = new HashMap<>();
    private final Clock clock;
    public AttemptWindow(Clock clock) { this.clock = clock; }

    public synchronized boolean allow(String key, int limit) {
        long now = clock.millis();
        windows.entrySet().removeIf(entry -> entry.getValue().until() <= now);
        Window window = windows.get(key);
        if (window == null) {
            if (windows.size() >= 10000) { return false; }
            windows.put(key, new Window(now + 60000, 1));
            return true;
        }
        if (window.count() >= limit) { return false; }
        windows.put(key, new Window(window.until(), window.count() + 1));
        return true;
    }
}
