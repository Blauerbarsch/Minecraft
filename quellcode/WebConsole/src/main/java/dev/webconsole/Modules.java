package dev.webconsole;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Finds the pages other plugins offer to the shared dashboard.
 *
 * <p>Contract (JDK types only, so no plugin needs the others to compile or run): a plugin registers a
 * {@code java.util.Map} with Bukkit's ServicesManager under the service class {@code Map.class}:
 * <pre>
 *   webhub  : 1                        marker + contract version
 *   id      : "stats"                  [a-z0-9-], becomes /m/stats/
 *   title   : "Statistik"              tab name
 *   order   : 20                       tab position
 *   access  : "member" | "admin"       who may open it (can be overridden in WebConsole's config)
 *   handler : Function&lt;Map,Map&gt;   request -> response
 * </pre>
 * Request map: method, path (inside the module, starts with "/"), query, user, role, body (byte[]).
 * Response map: status (int), type (String), body (byte[]), optional headers (Map) – only
 * Content-Disposition is passed through.
 *
 * <p>Bukkit removes a plugin's services when it is disabled, so tabs appear and disappear on their own.
 */
final class Modules {
    private Modules() {}

    @SuppressWarnings({"rawtypes", "unchecked"})
    static List<Map<String, Object>> find(ServicesManager sm, FileConfiguration cfg) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RegisteredServiceProvider<Map> r : sm.getRegistrations(Map.class)) {
            Map p = r.getProvider();
            if (p == null || !(p.get("webhub") instanceof Number)) continue;
            if (!(p.get("id") instanceof String id) || !id.matches("[a-z0-9-]{1,32}")) continue;
            if (!(p.get("handler") instanceof Function)) continue;
            String access = String.valueOf(p.getOrDefault("access", Users.MEMBER)).toLowerCase(Locale.ROOT);
            String override = cfg.getString("modules." + id, "");
            if (override != null && !override.isBlank()) access = override.toLowerCase(Locale.ROOT);
            if (access.equals("off")) continue;
            if (!access.equals(Users.ADMIN)) access = Users.MEMBER;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("title", String.valueOf(p.getOrDefault("title", id)));
            m.put("order", p.get("order") instanceof Number n ? n.intValue() : 50);
            m.put("access", access);
            m.put("plugin", r.getPlugin().getName());
            m.put("handler", p.get("handler"));
            out.add(m);
        }
        out.addAll(external(cfg));
        out.sort(Comparator.comparingInt(m -> (Integer) m.get("order")));
        return out;
    }

    /**
     * Tabs for other web apps (e.g. the BlueMap or squaremap live map), from "external-tabs" in config.yml.
     * proxy: the page is fetched by WebConsole and shown under /x/<id>/ – behind the login, one port.
     * url:   the page is embedded directly ({host} = the address the browser used for the dashboard).
     */
    static List<Map<String, Object>> external(FileConfiguration cfg) {
        List<Map<String, Object>> out = new ArrayList<>();
        int i = 0;
        for (Map<?, ?> t : cfg.getMapList("external-tabs")) {
            i++;
            String id = String.valueOf(t.get("id"));
            if (!id.matches("[a-z0-9-]{1,32}")) continue;
            String access = String.valueOf(t.get("access") == null ? Users.MEMBER : t.get("access")).toLowerCase(Locale.ROOT);
            if (access.equals("off")) continue;
            if (!access.equals(Users.ADMIN)) access = Users.MEMBER;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("title", t.get("title") == null ? id : String.valueOf(t.get("title")));
            m.put("order", t.get("order") instanceof Number n ? n.intValue() : 60 + i);
            m.put("access", access);
            m.put("plugin", "extern");
            Object proxy = t.get("proxy"), url = t.get("url");
            if (proxy != null && String.valueOf(proxy).matches("https?://.+")) {
                String base = String.valueOf(proxy);
                m.put("proxy", base.endsWith("/") ? base : base + "/");
            } else if (url != null && String.valueOf(url).matches("https?://.+")) {
                m.put("url", String.valueOf(url));
            } else {
                continue;
            }
            out.add(m);
        }
        return out;
    }
}
