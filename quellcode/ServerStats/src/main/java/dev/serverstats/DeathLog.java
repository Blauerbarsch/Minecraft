package dev.serverstats;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Every death with its cause, stored in deaths.jsonl. Vanilla stats don't know about lava or falling. */
final class DeathLog {

    private final File file;
    private final Logger log;
    private final List<Map<String, Object>> deaths = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ServerStats-IO");
        t.setDaemon(true);
        return t;
    });

    DeathLog(File file, Logger log) {
        this.file = file;
        this.log = log;
    }

    @SuppressWarnings("unchecked")
    void load() {
        if (!file.isFile()) return;
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try { deaths.add((Map<String, Object>) Json.parse(line)); } catch (Exception ignored) { }
            }
        } catch (IOException e) {
            log.warning("deaths.jsonl nicht lesbar: " + e.getMessage());
        }
    }

    /** Main thread only. */
    void add(Map<String, Object> d) {
        deaths.add(d);
        String line = Json.write(d) + "\n";
        io.execute(() -> {
            file.getParentFile().mkdirs();
            try (BufferedWriter w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(line);
            } catch (IOException e) {
                log.warning("Tod konnte nicht gespeichert werden: " + e.getMessage());
            }
        });
    }

    /** Main thread only. */
    List<Map<String, Object>> all() { return deaths; }

    void close() {
        io.shutdown();
        try { io.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
    }
}
