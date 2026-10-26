package dev.opwatch;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The dashboard data. {@link #route} answers requests – either from the WebConsole dashboard
 * or from the plugin's own small web server (only started when WebConsole is not installed).
 */
final class WebServer {

    private final JavaPlugin plugin;
    private final EventStore store;
    private final CommandCategorizer cats;
    private final String bind;
    private final int port;
    private final byte[] expectedAuth;
    private HttpServer server;
    private ExecutorService pool;
    private BukkitTask onlineTask;
    private volatile List<Map<String, Object>> online = List.of();
    private byte[] indexHtml = new byte[0];

    WebServer(JavaPlugin plugin, EventStore store, CommandCategorizer cats,
              String bind, int port, String user, String password) {
        this.plugin = plugin;
        this.store = store;
        this.cats = cats;
        this.bind = bind;
        this.port = port;
        this.expectedAuth = ("Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)))
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Loads the page and starts the online-player cache. Needed in both modes. */
    void startData() throws IOException {
        try (InputStream in = plugin.getResource("web/index.html")) {
            indexHtml = in == null ? "index.html fehlt".getBytes(StandardCharsets.UTF_8) : in.readAllBytes();
        }
        // online player list is read on the main thread and cached for the web threads
        onlineTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            List<Map<String, Object>> l = new ArrayList<>();
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", p.getName());
                m.put("gm", p.getGameMode().name().toLowerCase(Locale.ROOT));
                m.put("op", p.isOp());
                MonitorListener.putLoc(m, p.getLocation());
                l.add(m);
            }
            online = l;
        }, 20L, 40L);
    }

    /** Own web server with its own login (only when WebConsole is not used). */
    void startHttp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "OpWatch-Web");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
    }

    void stop() {
        if (onlineTask != null) onlineTask.cancel();
        if (server != null) server.stop(0);
        if (pool != null) pool.shutdownNow();
    }

    // ------------------------------------------------------------------ shared request logic

    /** Answers one GET request. Safe to call from any thread. */
    Map<String, Object> route(String path, String rawQuery) {
        Map<String, String> q = query(rawQuery);
        try {
            switch (path) {
                case "/", "/index.html" -> { return HubLink.html(indexHtml); }
                case "/api/events" -> {
                    long since = parseLong(q.get("since"), 0);
                    int limit = (int) Math.min(50000, parseLong(q.get("limit"), 20000));
                    var events = store.since(since, limit);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("events", events);
                    out.put("last", events.isEmpty() ? since : events.get(events.size() - 1).get("id"));
                    return HubLink.json(Json.write(out));
                }
                case "/api/meta" -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("categories", cats.labels());
                    out.put("online", online);
                    out.put("files", store.logFiles());
                    out.put("serverTime", System.currentTimeMillis());
                    return HubLink.json(Json.write(out));
                }
                case "/api/file" -> {
                    File f = store.logFile(q.getOrDefault("name", ""));
                    if (f == null) return HubLink.notFound();
                    Map<String, Object> r = HubLink.response(200, "application/x-ndjson; charset=utf-8", Files.readAllBytes(f.toPath()));
                    r.put("headers", Map.of("Content-Disposition", "attachment; filename=\"" + f.getName() + "\""));
                    return r;
                }
                default -> { return HubLink.notFound(); }
            }
        } catch (IOException e) {
            return HubLink.response(500, "text/plain", new byte[0]);
        }
    }

    /** Dashboard entry point (WebConsole). Access rights are checked by WebConsole. */
    Map<String, Object> hub(Map<String, Object> req) {
        if (!"GET".equals(req.get("method"))) return HubLink.response(405, "text/plain", new byte[0]);
        return route(String.valueOf(req.get("path")), (String) req.get("query"));
    }

    // ------------------------------------------------------------------ own web server

    private boolean authorized(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null) return false;
        return MessageDigest.isEqual(h.getBytes(StandardCharsets.UTF_8), expectedAuth);
    }

    @SuppressWarnings("unchecked")
    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!authorized(ex)) {
                ex.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"OpWatch\", charset=\"UTF-8\"");
                send(ex, 401, "text/plain; charset=utf-8", "Login erforderlich".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (!"GET".equals(ex.getRequestMethod())) {
                send(ex, 405, "text/plain", new byte[0]);
                return;
            }
            Map<String, Object> r = route(ex.getRequestURI().getPath(), ex.getRequestURI().getRawQuery());
            if (r.get("headers") instanceof Map<?, ?> h)
                for (Map.Entry<?, ?> e : h.entrySet()) ex.getResponseHeaders().set(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            send(ex, (Integer) r.get("status"), (String) r.get("type"), (byte[]) r.get("body"));
        } catch (Exception e) {
            plugin.getLogger().warning("Web-Fehler: " + e);
            try { send(ex, 500, "text/plain", new byte[0]); } catch (Exception ignored) { }
        } finally {
            ex.close();
        }
    }

    private static long parseLong(String s, long def) {
        try { return s == null ? def : Long.parseLong(s); } catch (NumberFormatException e) { return def; }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            m.put(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0) try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }
}
