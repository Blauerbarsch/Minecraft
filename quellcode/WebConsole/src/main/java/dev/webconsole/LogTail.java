package dev.webconsole;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Follows logs/latest.log like "tail -f" and keeps the last lines in memory.
 * Reading the file instead of hooking the logger means it sees exactly what the real console sees
 * and keeps working whatever logging setup the server uses. Handles the daily log rotation.
 */
final class LogTail {

    /** Lines containing this are never shown on the web (used for generated passwords). */
    static final String PRIVATE = "[nur Konsole]";

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[A-Za-z]");

    private record Line(long id, String text) {}

    private final File file;
    private final int max;
    private final List<String> hidden = new ArrayList<>();
    private final Logger log;
    private final ArrayDeque<Line> buf = new ArrayDeque<>();
    private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
    private long nextId = 1;
    private long pos = -1;
    private Object fileKey;
    private boolean skipFirst;
    private boolean warned;
    private ScheduledExecutorService exec;

    LogTail(File file, int max, List<String> hiddenContains, Logger log) {
        this.file = file;
        this.max = Math.max(200, max);
        for (String h : hiddenContains) if (h != null && !h.isBlank()) hidden.add(h.toLowerCase(Locale.ROOT));
        hidden.add(PRIVATE.toLowerCase(Locale.ROOT));
        this.log = log;
    }

    void start() {
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "WebConsole-Log");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(this::poll, 0, 250, TimeUnit.MILLISECONDS);
    }

    void stop() {
        if (exec != null) exec.shutdownNow();
    }

    private void poll() {
        try {
            if (!file.isFile()) {
                if (!warned) {
                    warned = true;
                    log.warning(file + " nicht gefunden – die Web-Konsole bleibt leer.");
                }
                return;
            }
            BasicFileAttributes a = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
            Object key = a.fileKey() != null ? a.fileKey() : a.creationTime();
            long len = a.size();
            if (pos < 0) {
                // first run: show the last part of the current log
                pos = Math.max(0, len - 256 * 1024);
                skipFirst = pos > 0;
                fileKey = key;
            } else if (!key.equals(fileKey) || len < pos) {
                fileKey = key;
                pos = 0;
                partial.reset();
                add("—— neue Log-Datei ——");
            }
            if (len <= pos) return;
            byte[] chunk = new byte[(int) Math.min(len - pos, 1 << 20)];
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.seek(pos);
                raf.readFully(chunk);
            }
            pos += chunk.length;
            for (byte b : chunk) {
                if (b == '\n') {
                    String s = partial.toString(StandardCharsets.UTF_8);
                    partial.reset();
                    if (skipFirst) { skipFirst = false; continue; }
                    add(s);
                } else {
                    partial.write(b);
                }
            }
        } catch (Exception ignored) {
            // file briefly unavailable during rotation – try again next round
        }
    }

    private void add(String raw) {
        String s = ANSI.matcher(raw).replaceAll("");
        if (s.endsWith("\r")) s = s.substring(0, s.length() - 1);
        String low = s.toLowerCase(Locale.ROOT);
        for (String h : hidden) if (low.contains(h)) return;
        synchronized (buf) {
            buf.addLast(new Line(nextId++, s));
            while (buf.size() > max) buf.removeFirst();
        }
    }

    /** Lines newer than {@code since}. reset=true tells the browser to clear its view first. */
    Map<String, Object> since(long since) {
        List<Map<String, Object>> out = new ArrayList<>();
        long last;
        boolean reset = false;
        synchronized (buf) {
            last = nextId - 1;
            if (since > last) reset = true;                               // plugin restarted, ids began again
            else if (since > 0 && !buf.isEmpty() && buf.peekFirst().id() > since + 1) reset = true; // fell too far behind
            long from = reset ? 0 : since;
            for (Line l : buf) {
                if (l.id() <= from) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", l.id());
                m.put("t", l.text());
                out.add(m);
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("lines", out);
        r.put("last", last);
        r.put("reset", reset);
        return r;
    }
}
