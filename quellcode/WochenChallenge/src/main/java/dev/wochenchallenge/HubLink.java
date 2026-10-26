package dev.wochenchallenge;

import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Puts this plugin's web page into the shared dashboard of the WebConsole plugin, if it is installed.
 * Only JDK types cross the border between the plugins (a Map registered with Bukkit's ServicesManager),
 * so neither plugin needs the other one to compile or to run.
 */
final class HubLink {
    private HubLink() {}

    static boolean hubInstalled(JavaPlugin p) {
        return p.getServer().getPluginManager().getPlugin("WebConsole") != null;
    }

    /** web.standalone: auto (default) = own web server only when WebConsole is missing, true/false = always/never. */
    static boolean ownServer(JavaPlugin p) {
        String v = String.valueOf(p.getConfig().getString("web.standalone", "auto")).toLowerCase(Locale.ROOT);
        if (v.equals("true")) return true;
        if (v.equals("false")) return false;
        return !hubInstalled(p);
    }

    static void register(JavaPlugin p, String id, String title, int order, String access,
                         Function<Map<String, Object>, Map<String, Object>> handler) {
        Map<String, Object> m = new HashMap<>();
        m.put("webhub", 1);
        m.put("id", id);
        m.put("title", title);
        m.put("order", order);
        m.put("access", access);
        m.put("handler", handler);
        p.getServer().getServicesManager().register(Map.class, m, p, ServicePriority.Normal);
    }

    static Map<String, Object> response(int status, String type, byte[] body) {
        Map<String, Object> r = new HashMap<>();
        r.put("status", status);
        r.put("type", type);
        r.put("body", body);
        return r;
    }

    static Map<String, Object> html(byte[] page) {
        return response(200, "text/html; charset=utf-8", page);
    }

    static Map<String, Object> json(String json) {
        return response(200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    static Map<String, Object> notFound() {
        return response(404, "text/plain; charset=utf-8", "404".getBytes(StandardCharsets.UTF_8));
    }
}
