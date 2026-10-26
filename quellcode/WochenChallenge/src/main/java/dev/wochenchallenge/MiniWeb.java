package dev.wochenchallenge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** Tiny JDK web server: "/" serves the page, "/api/data" the current JSON. Optional password. */
final class MiniWeb {

    private final Logger log;
    private final String bind;
    private final int port;
    private final String password;
    private final byte[] page;
    private final Supplier<String> data;
    private HttpServer server;
    private ExecutorService pool;

    MiniWeb(Logger log, String bind, int port, String password, byte[] page, Supplier<String> data) {
        this.log = log;
        this.bind = bind;
        this.port = port;
        this.password = password == null ? "" : password;
        this.page = page;
        this.data = data;
    }

    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "Web-" + port);
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

    private boolean authorized(HttpExchange ex) {
        if (password.isEmpty()) return true;
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.startsWith("Basic ")) return false;
        try {
            String dec = new String(Base64.getDecoder().decode(h.substring(6)), StandardCharsets.UTF_8);
            String pw = dec.substring(dec.indexOf(':') + 1);
            return MessageDigest.isEqual(pw.getBytes(StandardCharsets.UTF_8), password.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!authorized(ex)) {
                ex.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"Server\", charset=\"UTF-8\"");
                send(ex, 401, "text/plain; charset=utf-8", "Passwort erforderlich".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String path = ex.getRequestURI().getPath();
            if (path.equals("/") || path.equals("/index.html")) {
                send(ex, 200, "text/html; charset=utf-8", page);
            } else if (path.equals("/api/data")) {
                send(ex, 200, "application/json; charset=utf-8", data.get().getBytes(StandardCharsets.UTF_8));
            } else {
                send(ex, 404, "text/plain", "404".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            log.warning("Web-Fehler: " + e);
        } finally {
            ex.close();
        }
    }

    private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0) try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }
}
