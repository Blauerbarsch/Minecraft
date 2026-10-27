package dev.serverstats;

import org.bukkit.entity.Player;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Live state of online players plus the last known state of everyone who left
 * (Bukkit can't read the armor of offline players, so it is remembered in players.json).
 * Builds two JSON versions: the full one for admins and a reduced one for members.
 */
final class PlayerSnapshots {

    /** Fields only admins get unless the config allows them for members. */
    private static final String LOC = "loc", INV = "inventory", ENDER = "enderchest";

    private final File file;
    private final Logger log;
    private final boolean membersLoc, membersInv;
    private final Map<UUID, Map<String, Object>> online = new HashMap<>();
    private final Map<UUID, Map<String, Object>> known = new HashMap<>();
    private final Map<UUID, Long> joined = new HashMap<>();
    /** Shown in the page header of the standalone players page. */
    volatile String serverName = "";
    private volatile String jsonAdmin = "{\"players\":[]}";
    private volatile String jsonMember = "{\"players\":[]}";
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ServerStats-Players");
        t.setDaemon(true);
        return t;
    });

    PlayerSnapshots(File file, Logger log, boolean membersLoc, boolean membersInv) {
        this.file = file;
        this.log = log;
        this.membersLoc = membersLoc;
        this.membersInv = membersInv;
    }

    String json(boolean admin) { return admin ? jsonAdmin : jsonMember; }

    // ------------------------------------------------------------------ main thread

    void joined(Player p) {
        joined.put(p.getUniqueId(), System.currentTimeMillis());
        capture(p);
    }

    void capture(Player p) {
        try {
            Map<String, Object> s = ItemInfo.snapshot(p);
            s.put("online", true);
            s.put("since", joined.computeIfAbsent(p.getUniqueId(), k -> System.currentTimeMillis()));
            online.put(p.getUniqueId(), s);
        } catch (Throwable t) {
            log.warning("Spielerdaten von " + p.getName() + " konnten nicht gelesen werden: " + t);
        }
    }

    /** Player leaves (or server stops): keep the last state, write it to disk. */
    void left(Player p) {
        capture(p);
        Map<String, Object> s = online.remove(p.getUniqueId());
        joined.remove(p.getUniqueId());
        if (s == null) return;
        s.put("online", false);
        s.put("lastSeen", System.currentTimeMillis());
        s.remove("ping");
        s.remove("since");
        known.put(p.getUniqueId(), s);
    }

    /** Merges live data, remembered data, vanilla stats and the last death into the two JSON versions. */
    void rebuild(Map<UUID, Map<String, Object>> rows, List<Map<String, Object>> deaths, Set<String> hidden) {
        Map<String, Map<String, Object>> lastDeath = new HashMap<>();
        for (Map<String, Object> d : deaths) lastDeath.put(String.valueOf(d.get("uuid")), d);

        List<Map<String, Object>> admin = new ArrayList<>(), member = new ArrayList<>();
        Map<UUID, Map<String, Object>> all = new LinkedHashMap<>(known);
        all.putAll(online);
        for (Map.Entry<UUID, Map<String, Object>> e : all.entrySet()) {
            Map<String, Object> s = new LinkedHashMap<>(e.getValue());
            if (hidden.contains(String.valueOf(s.get("name")).toLowerCase(java.util.Locale.ROOT))) continue;
            Map<String, Object> row = rows.get(e.getKey());
            if (row != null) s.put("stats", row.get("stats"));
            Map<String, Object> d = lastDeath.get(e.getKey().toString());
            if (d != null) {
                Map<String, Object> ld = new LinkedHashMap<>();
                ld.put("ts", d.get("ts"));
                ld.put("cause", d.get("cause"));
                if (d.get("by") != null) ld.put("by", d.get("by"));
                s.put("lastDeath", ld);
            }
            admin.add(s);
            Map<String, Object> m = new LinkedHashMap<>(s);
            if (!membersLoc) m.remove(LOC);
            if (!membersInv) { m.remove(INV); m.remove(ENDER); }
            member.add(m);
        }
        jsonAdmin = Json.write(wrap(admin, true));
        jsonMember = Json.write(wrap(member, false));
    }

    private Map<String, Object> wrap(List<Map<String, Object>> players, boolean admin) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generated", System.currentTimeMillis());
        out.put("serverName", serverName);
        out.put("full", admin);
        out.put("showLocation", admin || membersLoc);
        out.put("showInventory", admin || membersInv);
        out.put("players", players);
        return out;
    }

    // ------------------------------------------------------------------ persistence

    @SuppressWarnings("unchecked")
    void load() {
        if (!file.isFile()) return;
        try {
            Map<String, Object> m = (Map<String, Object>) Json.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            for (Map.Entry<String, Object> e : m.entrySet()) {
                Map<String, Object> s = (Map<String, Object>) e.getValue();
                s.put("online", false);
                known.put(UUID.fromString(e.getKey()), s);
            }
        } catch (Exception e) {
            log.warning("players.json nicht lesbar – letzte Ausrüstung offline Spieler fehlt bis zum nächsten Login.");
        }
    }

    void saveAsync() {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<UUID, Map<String, Object>> e : known.entrySet()) copy.put(e.getKey().toString(), e.getValue());
        String text = Json.write(copy);
        io.execute(() -> write(text));
    }

    private void write(String text) {
        try {
            file.getParentFile().mkdirs();
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            Files.writeString(tmp.toPath(), text, StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.warning("players.json konnte nicht gespeichert werden: " + e.getMessage());
        }
    }

    /** Server stops: remember everyone who is still online, then wait for the file. */
    void shutdown(Iterable<? extends Player> players) {
        for (Player p : players) left(p);
        saveAsync();
        io.shutdown();
        try { io.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
    }
}
