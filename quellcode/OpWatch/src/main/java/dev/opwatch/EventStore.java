package dev.opwatch;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Keeps the most recent events in memory for the dashboard and appends every event
 * to a daily JSON-lines file (plugins/OpWatch/logs/yyyy-MM-dd.jsonl). File writes run on
 * a background thread so the server tick is never blocked.
 */
final class EventStore {

    private final File dir;
    private final int maxMemory;
    private final int loadDays;
    private final Logger log;
    private final Deque<Map<String, Object>> events = new ArrayDeque<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "OpWatch-IO");
        t.setDaemon(true);
        return t;
    });

    EventStore(File dir, int maxMemory, int loadDays, Logger log) {
        this.dir = dir;
        this.maxMemory = Math.max(1000, maxMemory);
        this.loadDays = Math.max(0, loadDays);
        this.log = log;
        dir.mkdirs();
    }

    void loadHistory() {
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> loaded = new ArrayList<>();
        for (int d = loadDays; d >= 0; d--) {
            File f = new File(dir, today.minusDays(d) + ".jsonl");
            if (!f.isFile()) continue;
            try {
                for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) Json.parse(line);
                        loaded.add(m);
                    } catch (Exception ignored) { }
                }
            } catch (IOException e) {
                log.warning("Konnte " + f.getName() + " nicht lesen: " + e.getMessage());
            }
        }
        synchronized (events) {
            for (Map<String, Object> m : loaded) {
                m.put("id", nextId.getAndIncrement());
                events.addLast(m);
                if (events.size() > maxMemory) events.removeFirst();
            }
        }
        log.info(loaded.size() + " Log-Einträge aus den letzten " + loadDays + " Tagen geladen.");
    }

    /** Adds an event. Thread-safe. */
    void add(Map<String, Object> e) {
        e.putIfAbsent("ts", System.currentTimeMillis());
        e.put("id", nextId.getAndIncrement());
        synchronized (events) {
            events.addLast(e);
            if (events.size() > maxMemory) events.removeFirst();
        }
        // write without the volatile id
        Map<String, Object> copy = new java.util.LinkedHashMap<>(e);
        copy.remove("id");
        String line = Json.write(copy) + "\n";
        long ts = ((Number) e.get("ts")).longValue();
        io.execute(() -> {
            LocalDate day = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate();
            File f = new File(dir, day + ".jsonl");
            try (BufferedWriter w = Files.newBufferedWriter(f.toPath(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(line);
            } catch (IOException ex) {
                log.warning("Log schreiben fehlgeschlagen: " + ex.getMessage());
            }
        });
    }

    /** Events with id greater than {@code since}, newest last, at most {@code limit}. */
    List<Map<String, Object>> since(long since, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (events) {
            var it = events.descendingIterator();
            while (it.hasNext() && out.size() < limit) {
                Map<String, Object> e = it.next();
                if (((Number) e.get("id")).longValue() <= since) break;
                out.add(e);
            }
        }
        Collections.reverse(out);
        return out;
    }

    List<String> logFiles() {
        String[] names = dir.list((d, n) -> n.endsWith(".jsonl"));
        List<String> l = new ArrayList<>();
        if (names != null) Collections.addAll(l, names);
        Collections.sort(l);
        Collections.reverse(l);
        return l;
    }

    File logFile(String name) {
        if (!name.matches("\\d{4}-\\d{2}-\\d{2}\\.jsonl")) return null;
        File f = new File(dir, name);
        return f.isFile() ? f : null;
    }

    void close() {
        io.shutdown();
        try { io.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
    }
}
