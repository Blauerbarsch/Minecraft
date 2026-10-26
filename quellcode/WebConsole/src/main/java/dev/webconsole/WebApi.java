package dev.webconsole;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * The web console: one page plus a small JSON API.
 *
 * Security:
 *  - login via session cookie (HttpOnly, SameSite=Strict); passwords hashed with PBKDF2
 *  - every POST needs the header "X-WebConsole: 1", which other websites can't send (CSRF)
 *  - the role is looked up on every request, so role changes and deletions apply immediately
 *  - failed logins are rate limited per IP
 *  - members get IP addresses in the log masked
 */
final class WebApi {

    private static final String COOKIE = "wc_session";
    private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?\\b");
    private static final Pattern IPV6 = Pattern.compile("/\\[[0-9a-fA-F:.%]+\\](?::\\d{1,5})?");

    private final Backend backend;
    private final Users users;
    private final Sessions sessions;
    private final LogTail tail;
    private final byte[] page;
    private final String bind;
    private final int port;
    private final boolean maskIps;
    private final Logger log;
    private final Map<String, long[]> attempts = new ConcurrentHashMap<>(); // ip -> {fails, blockedUntil}
    private HttpServer server;
    private ExecutorService pool;

    WebApi(Backend backend, Users users, Sessions sessions, LogTail tail, byte[] page,
           String bind, int port, boolean maskIps, Logger log) {
        this.backend = backend;
        this.users = users;
        this.sessions = sessions;
        this.tail = tail;
        this.page = page;
        this.bind = bind;
        this.port = port;
        this.maskIps = maskIps;
        this.log = log;
    }

    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "WebConsole-Web");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
    }

    void stop() {
        if (server != null) server.stop(0);
        if (pool != null) pool.shutdownNow();
    }

    // ====================================================================== routing

    private void handle(HttpExchange ex) {
        try {
            Headers h = ex.getResponseHeaders();
            // pages of other plugins are shown in frames of this page, so allow framing by ourselves only
            h.set("X-Frame-Options", "SAMEORIGIN");
            h.set("Content-Security-Policy", "frame-ancestors 'self'");
            h.set("X-Content-Type-Options", "nosniff");
            h.set("Referrer-Policy", "no-referrer");
            h.set("Cache-Control", "no-store");

            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.equals("/") || path.equals("/index.html")) {
                if (!method.equals("GET") && !method.equals("HEAD")) { send(ex, 405, "text/plain", new byte[0]); return; }
                send(ex, 200, "text/html; charset=utf-8", page);
                return;
            }
            if (path.startsWith("/m/")) { module(ex, path, method); return; }
            if (path.startsWith("/x/")) { external(ex, path, method); return; }
            if (!path.startsWith("/api/")) { send(ex, 404, "text/plain", "404".getBytes(StandardCharsets.UTF_8)); return; }

            boolean post = method.equals("POST");
            if (!post && !method.equals("GET")) { error(ex, 405, "Methode nicht erlaubt."); return; }
            if (post && !"1".equals(ex.getRequestHeaders().getFirst("X-WebConsole"))) { error(ex, 403, "Anfrage abgelehnt."); return; }

            Map<String, Object> body = post ? body(ex) : Map.of();
            String ip = ex.getRemoteAddress().getAddress().getHostAddress();

            if (post && path.equals("/api/login")) { login(ex, body, ip); return; }

            String token = cookie(ex);
            String user = token == null ? null : sessions.user(token);
            String role = user == null ? null : users.role(user);
            if (role == null) {
                if (post && path.equals("/api/logout")) { clearCookie(ex); json(ex, 200, Map.of("ok", true)); return; }
                error(ex, 401, "Bitte melde dich an.");
                return;
            }
            user = users.displayName(user);
            boolean admin = Users.ADMIN.equals(role);

            switch (method + " " + path) {
                case "GET /api/me" -> json(ex, 200, me(user, role));
                case "POST /api/logout" -> {
                    sessions.remove(token);
                    clearCookie(ex);
                    json(ex, 200, Map.of("ok", true));
                }
                case "GET /api/modules" -> {
                    List<Map<String, Object>> l = new java.util.ArrayList<>();
                    for (Map<String, Object> m : backend.modules()) {
                        if (!admin && Users.ADMIN.equals(m.get("access"))) continue;
                        Map<String, Object> v = new LinkedHashMap<>();
                        v.put("id", m.get("id"));
                        v.put("title", m.get("title"));
                        v.put("access", m.get("access"));
                        if (m.get("proxy") != null) v.put("kind", "proxy");
                        else if (m.get("url") != null) { v.put("kind", "url"); v.put("url", m.get("url")); }
                        else v.put("kind", "plugin");
                        l.add(v);
                    }
                    json(ex, 200, Map.of("modules", l));
                }
                case "GET /api/console" -> console(ex, admin);
                case "GET /api/status" -> status(ex, admin);
                case "POST /api/command" -> {
                    if (!admin) { error(ex, 403, "Nur Admins dürfen Befehle senden."); return; }
                    command(ex, body, user, ip);
                }
                case "POST /api/password" -> password(ex, body, user, ip);
                case "GET /api/users" -> {
                    if (!admin) { error(ex, 403, "Nur für Admins."); return; }
                    json(ex, 200, Map.of("users", users.list()));
                }
                case "POST /api/users" -> {
                    if (!admin) { error(ex, 403, "Nur für Admins."); return; }
                    manage(ex, body, user, ip);
                }
                default -> error(ex, 404, "Nicht gefunden.");
            }
        } catch (Exception e) {
            log.warning("Web-Fehler: " + e);
            try { error(ex, 500, "Interner Fehler."); } catch (Exception ignored) { }
        } finally {
            ex.close();
        }
    }

    // ====================================================================== plugin pages (/m/<id>/...)

    @SuppressWarnings("unchecked")
    private void module(HttpExchange ex, String path, String method) throws IOException {
        String rest = path.substring(3);
        int slash = rest.indexOf('/');
        String id = slash < 0 ? rest : rest.substring(0, slash);
        if (slash < 0) {
            // /m/stats -> /m/stats/ so that relative links inside the page work
            String q = ex.getRequestURI().getRawQuery();
            ex.getResponseHeaders().set("Location", "/m/" + id + "/" + (q == null ? "" : "?" + q));
            send(ex, 302, "text/plain", new byte[0]);
            return;
        }
        String sub = rest.substring(slash);

        String token = cookie(ex);
        String user = token == null ? null : sessions.user(token);
        String role = user == null ? null : users.role(user);
        if (role == null) { error(ex, 401, "Bitte melde dich an."); return; }
        boolean post = method.equals("POST");
        if (!post && !method.equals("GET")) { error(ex, 405, "Methode nicht erlaubt."); return; }
        if (post && !"1".equals(ex.getRequestHeaders().getFirst("X-WebConsole"))) { error(ex, 403, "Anfrage abgelehnt."); return; }

        Map<String, Object> mod = null;
        for (Map<String, Object> m : backend.modules()) if (id.equals(m.get("id")) && m.get("handler") != null) mod = m;
        if (mod == null) { error(ex, 404, "Diese Seite gibt es nicht (mehr). Ist das Plugin geladen?"); return; }
        if (Users.ADMIN.equals(mod.get("access")) && !Users.ADMIN.equals(role)) { error(ex, 403, "Nur für Admins."); return; }

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("method", method);
        req.put("path", sub);
        req.put("query", ex.getRequestURI().getRawQuery());
        req.put("user", users.displayName(user));
        req.put("role", role);
        if (post) try (InputStream in = ex.getRequestBody()) { req.put("body", in.readNBytes(1 << 20)); }

        Map<String, Object> res;
        try {
            res = ((java.util.function.Function<Map<String, Object>, Map<String, Object>>) mod.get("handler")).apply(req);
        } catch (Throwable t) {
            log.warning("Fehler in der Seite von " + mod.get("plugin") + ": " + t);
            error(ex, 500, "Das Plugin " + mod.get("plugin") + " hat einen Fehler gemeldet.");
            return;
        }
        if (res == null) { error(ex, 404, "Nicht gefunden."); return; }
        int status = res.get("status") instanceof Number n ? n.intValue() : 200;
        String type = res.get("type") instanceof String s ? s : "application/octet-stream";
        byte[] body = res.get("body") instanceof byte[] b ? b : new byte[0];
        if (res.get("headers") instanceof Map<?, ?> hm && hm.get("Content-Disposition") instanceof String cd)
            ex.getResponseHeaders().set("Content-Disposition", cd);
        send(ex, status, type, body);
    }

    // ====================================================================== other web apps (/x/<id>/...)

    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(3))
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
            .build();
    private static final String[] PASS_REQUEST = {"Accept", "Accept-Encoding", "Accept-Language", "If-None-Match", "If-Modified-Since", "Range"};
    private static final String[] PASS_RESPONSE = {"Content-Type", "Content-Encoding", "Cache-Control", "ETag", "Last-Modified",
            "Expires", "Content-Range", "Accept-Ranges"};

    /** Fetches pages of e.g. BlueMap/squaremap for logged-in users only – the map itself can stay on 127.0.0.1. */
    private void external(HttpExchange ex, String path, String method) throws IOException {
        String rest = path.substring(3);
        int slash = rest.indexOf('/');
        String id = slash < 0 ? rest : rest.substring(0, slash);
        if (slash < 0) {
            String q = ex.getRequestURI().getRawQuery();
            ex.getResponseHeaders().set("Location", "/x/" + id + "/" + (q == null ? "" : "?" + q));
            send(ex, 302, "text/plain", new byte[0]);
            return;
        }
        String token = cookie(ex);
        String user = token == null ? null : sessions.user(token);
        String role = user == null ? null : users.role(user);
        if (role == null) { error(ex, 401, "Bitte melde dich an."); return; }
        if (!method.equals("GET") && !method.equals("HEAD")) { error(ex, 405, "Nur Lesen erlaubt."); return; }

        Map<String, Object> tab = null;
        for (Map<String, Object> m : backend.modules()) if (id.equals(m.get("id")) && m.get("proxy") != null) tab = m;
        if (tab == null) { error(ex, 404, "Diesen Tab gibt es nicht."); return; }
        if (Users.ADMIN.equals(tab.get("access")) && !Users.ADMIN.equals(role)) { error(ex, 403, "Nur für Admins."); return; }

        String rawSub = ex.getRequestURI().getRawPath().substring(3 + id.length() + 1); // without the leading "/"
        if (rawSub.contains("..")) { error(ex, 400, "Ungültiger Pfad."); return; }
        String q = ex.getRequestURI().getRawQuery();
        java.net.URI target;
        try {
            target = java.net.URI.create(tab.get("proxy") + rawSub + (q == null ? "" : "?" + q));
        } catch (IllegalArgumentException e) {
            error(ex, 400, "Ungültiger Pfad.");
            return;
        }
        java.net.http.HttpRequest.Builder rb = java.net.http.HttpRequest.newBuilder(target)
                .timeout(java.time.Duration.ofSeconds(30))
                .method(method, java.net.http.HttpRequest.BodyPublishers.noBody());
        for (String h : PASS_REQUEST) {
            String v = ex.getRequestHeaders().getFirst(h);
            if (v != null) rb.header(h, v);
        }
        java.net.http.HttpResponse<InputStream> r;
        try {
            r = HTTP.send(rb.build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            String msg = "<!doctype html><meta charset=utf-8><body style=\"font:16px system-ui;background:#15121c;color:#ece7f5;padding:40px\">"
                    + "<h2 style=\"font-weight:600\">„" + escape(String.valueOf(tab.get("title"))) + "“ ist gerade nicht erreichbar</h2>"
                    + "<p style=\"color:#9b93b0;max-width:60ch\">Die WebConsole hat unter <code>" + escape(String.valueOf(tab.get("proxy")))
                    + "</code> niemanden erreicht. Ist das Karten-Plugin (z.&nbsp;B. BlueMap) installiert und gestartet, "
                    + "und stimmt der Port in <code>plugins/WebConsole/config.yml</code> unter <code>external-tabs</code>?</p>";
            send(ex, 502, "text/html; charset=utf-8", msg.getBytes(StandardCharsets.UTF_8));
            return;
        }
        Headers out = ex.getResponseHeaders();
        out.remove("Cache-Control"); // the map decides how long its tiles may be cached
        for (String h : PASS_RESPONSE) r.headers().firstValue(h).ifPresent(v -> out.set(h, v));
        r.headers().firstValue("Location").ifPresent(loc -> {
            // keep redirects inside /x/<id>/
            if (loc.startsWith("/")) out.set("Location", "/x/" + id + loc);
            else if (!loc.matches("^[a-zA-Z]+://.*")) out.set("Location", loc);
        });
        int status = r.statusCode();
        long len = r.headers().firstValueAsLong("Content-Length").orElse(-1);
        boolean noBody = method.equals("HEAD") || status == 204 || status == 304;
        try (InputStream in = r.body()) {
            if (noBody) {
                ex.sendResponseHeaders(status, -1);
            } else {
                ex.sendResponseHeaders(status, len >= 0 ? (len == 0 ? -1 : len) : 0);
                if (len != 0) try (OutputStream os = ex.getResponseBody()) { in.transferTo(os); }
            }
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ====================================================================== handlers

    private void login(HttpExchange ex, Map<String, Object> body, String ip) throws IOException {
        long now = System.currentTimeMillis();
        long[] a = attempts.get(ip);
        if (a != null && a[1] > now) {
            long sec = (a[1] - now + 999) / 1000;
            error(ex, 429, "Zu viele Fehlversuche. Versuch es in " + sec + " Sekunden nochmal.");
            return;
        }
        String name = str(body.get("user"));
        String pw = str(body.get("pass"));
        String ok = users.verify(name, pw);
        if (ok == null) {
            long[] na = attempts.compute(ip, (k, v) -> v == null ? new long[]{0, 0} : v);
            synchronized (na) {
                na[0]++;
                if (na[0] >= 5) na[1] = System.currentTimeMillis() + Math.min(15 * 60_000L, 30_000L << Math.min(5, na[0] - 5));
            }
            backend.audit(name == null ? "?" : name, ip, "fehlgeschlagener Login", false);
            error(ex, 401, "Benutzername oder Passwort stimmt nicht.");
            return;
        }
        attempts.remove(ip);
        setCookie(ex, sessions.create(ok));
        backend.audit(ok, ip, "hat sich angemeldet", false);
        json(ex, 200, me(ok, users.role(ok)));
    }

    private static Map<String, Object> me(String user, String role) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", user);
        m.put("role", role);
        return m;
    }

    @SuppressWarnings("unchecked")
    private void console(HttpExchange ex, boolean admin) throws IOException {
        long since = 0;
        String q = ex.getRequestURI().getRawQuery();
        if (q != null) for (String part : q.split("&")) {
            if (part.startsWith("since=")) {
                try { since = Long.parseLong(URLDecoder.decode(part.substring(6), StandardCharsets.UTF_8)); } catch (NumberFormatException ignored) { }
            }
        }
        Map<String, Object> r = tail.since(Math.max(0, since));
        if (!admin && maskIps) {
            for (Map<String, Object> l : (List<Map<String, Object>>) r.get("lines")) l.put("t", mask((String) l.get("t")));
        }
        json(ex, 200, r);
    }

    static String mask(String s) {
        s = IPV6.matcher(s).replaceAll("/[IP verborgen]");
        return IPV4.matcher(s).replaceAll("x.x.x.x");
    }

    private void status(HttpExchange ex, boolean admin) throws IOException {
        Map<String, Object> s = new LinkedHashMap<>(backend.status());
        if (!admin) s.remove("menu");
        json(ex, 200, s);
    }

    private void command(HttpExchange ex, Map<String, Object> body, String user, String ip) throws IOException {
        String cmd = str(body.get("command"));
        cmd = cmd == null ? "" : cmd.strip();
        while (cmd.startsWith("/")) cmd = cmd.substring(1);
        if (cmd.isEmpty()) { error(ex, 400, "Kein Befehl angegeben."); return; }
        if (cmd.length() > 1000 || cmd.contains("\n") || cmd.contains("\r")) { error(ex, 400, "Der Befehl ist zu lang oder ungültig."); return; }
        backend.runCommand(user, ip, cmd);
        json(ex, 200, Map.of("ok", true));
    }

    private void password(HttpExchange ex, Map<String, Object> body, String user, String ip) throws IOException {
        String old = str(body.get("old"));
        String neu = str(body.get("new"));
        if (users.verify(user, old) == null) { error(ex, 400, "Das aktuelle Passwort stimmt nicht."); return; }
        try {
            users.setPassword(user, neu);
        } catch (IllegalArgumentException e) {
            error(ex, 400, e.getMessage());
            return;
        }
        sessions.invalidateUser(user);
        setCookie(ex, sessions.create(user));
        backend.audit(user, ip, "hat das eigene Passwort geändert", false);
        json(ex, 200, Map.of("ok", true));
    }

    private void manage(HttpExchange ex, Map<String, Object> body, String user, String ip) throws IOException {
        String action = str(body.get("action"));
        String name = str(body.get("name"));
        String role = str(body.get("role"));
        boolean self = name != null && name.equalsIgnoreCase(user);
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            switch (action == null ? "" : action) {
                case "create" -> {
                    String pw = users.create(name, role);
                    out.put("password", pw);
                    backend.audit(user, ip, "hat das Konto " + name + " (" + role + ") angelegt", true);
                }
                case "role" -> {
                    if (self) throw new IllegalArgumentException("Deine eigene Rolle kannst du nicht ändern.");
                    users.setRole(name, role);
                    backend.audit(user, ip, "hat " + users.displayName(name) + " zu " + role + " gemacht", true);
                }
                case "reset" -> {
                    if (self) throw new IllegalArgumentException("Für dein eigenes Konto nutz „Passwort ändern“.");
                    if (users.role(name) == null) throw new IllegalArgumentException("Das Konto gibt es nicht.");
                    out.put("password", users.resetPassword(name));
                    sessions.invalidateUser(name);
                    backend.audit(user, ip, "hat das Passwort von " + users.displayName(name) + " zurückgesetzt", true);
                }
                case "delete" -> {
                    if (self) throw new IllegalArgumentException("Dein eigenes Konto kannst du nicht löschen.");
                    String shown = users.displayName(name);
                    users.delete(name);
                    sessions.invalidateUser(name);
                    backend.audit(user, ip, "hat das Konto " + shown + " gelöscht", true);
                }
                default -> throw new IllegalArgumentException("Unbekannte Aktion.");
            }
            out.put("ok", true);
            json(ex, 200, out);
        } catch (IllegalArgumentException e) {
            error(ex, 400, e.getMessage());
        }
    }

    // ====================================================================== http helpers

    private static String str(Object o) {
        return o instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(HttpExchange ex) {
        try (InputStream in = ex.getRequestBody()) {
            byte[] b = in.readNBytes(64 * 1024);
            if (b.length == 0) return Map.of();
            Object o = Json.parse(new String(b, StandardCharsets.UTF_8));
            return o instanceof Map ? (Map<String, Object>) o : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String cookie(HttpExchange ex) {
        List<String> all = ex.getRequestHeaders().get("Cookie");
        if (all == null) return null;
        for (String header : all) for (String c : header.split(";")) {
            String t = c.trim();
            if (t.startsWith(COOKIE + "=")) return t.substring(COOKIE.length() + 1);
        }
        return null;
    }

    private void setCookie(HttpExchange ex, String token) {
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + sessions.maxAgeSeconds());
    }

    private static void clearCookie(HttpExchange ex) {
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
    }

    private static void error(HttpExchange ex, int code, String msg) throws IOException {
        json(ex, code, Map.of("error", msg));
    }

    private static void json(HttpExchange ex, int code, Object o) throws IOException {
        send(ex, code, "application/json; charset=utf-8", Json.write(o).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        boolean head = ex.getRequestMethod().equals("HEAD");
        ex.sendResponseHeaders(code, body.length == 0 || head ? -1 : body.length);
        if (body.length > 0 && !head) try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }
}
