package dev.muelleimer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The last emptied trash of every player, kept for a short time so a wrong click can be undone.
 * Everything thrown away within that time can be taken back at once.
 * Main thread only. Gone after a restart – the trash is supposed to delete things.
 */
final class UndoBuffer<T> {

    private record Entry<T>(List<T> items, long until) {}

    private final Map<UUID, Entry<T>> map = new HashMap<>();
    private final long keepMillis;

    UndoBuffer(long keepMillis) {
        this.keepMillis = keepMillis;
    }

    /** Adds to what is still waiting (so two quick loads can both be undone) and restarts the timer. */
    void put(UUID player, List<T> items, long now) {
        if (items.isEmpty()) return;
        List<T> all = new ArrayList<>();
        Entry<T> old = map.get(player);
        if (old != null && old.until() >= now) all.addAll(old.items());
        all.addAll(items);
        map.put(player, new Entry<>(all, now + keepMillis));
    }

    /** Takes the items out (only once). Empty list if nothing or too late. */
    List<T> take(UUID player, long now) {
        Entry<T> e = map.remove(player);
        if (e == null || e.until() < now) return List.of();
        return e.items();
    }

    /** Seconds left for /trash undo, or 0. */
    long secondsLeft(UUID player, long now) {
        Entry<T> e = map.get(player);
        return e == null || e.until() < now ? 0 : (e.until() - now + 999) / 1000;
    }

    void purge(long now) {
        map.values().removeIf(e -> e.until() < now);
    }
}
