package dev.serverstats;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.logging.Logger;

/** Own small web server (only used when WebConsole is not installed). Optional password. */
final class MiniWeb {

    private final Logger log;
    private final String bind;
    private final int port;
    private final String password;
    private final Function<String, Map<String, Object>> route;
    private HttpServer server;
    private ExecutorService pool;

    MiniWeb(Logger log, String bind, int port, String password, Function<String, Map<String, Object>> route) {
        this.log = log;
        this.bind = bind;
        this.port = port;
        this.password = password == null ? "" : password;
        this.route = route;
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
            if (path.equals("/spieler")) { // relative links on that page need the trailing slash
                ex.getResponseHeaders().set("Location", "/spieler/");
                send(ex, 302, "text/plain", new byte[0]);
                return;
            }
            Map<String, Object> r = route.apply(path);
            send(ex, (Integer) r.get("status"), (String) r.get("type"), (byte[]) r.get("body"));
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
