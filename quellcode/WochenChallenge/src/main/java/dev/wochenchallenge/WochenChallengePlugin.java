package dev.wochenchallenge;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class WochenChallengePlugin extends JavaPlugin implements Listener, CommandExecutor {


    /** progress of one player in the running week */
    private static final class Entry {
        String name;
        long raw;
        boolean creative;
        long finishedAt;
    }

    private final Map<String, Challenge> challenges = new LinkedHashMap<>();
    private final Map<UUID, Entry> progress = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> baseline = new HashMap<>();
    private final Map<UUID, Boolean> lastCountable = new HashMap<>();
    private final Map<String, List<String>> pendingRewards = new HashMap<>();
    private final List<Map<String, Object>> history = new ArrayList<>();
    private final Random random = new Random();

    private Challenge current;
    private long weekStart, weekEnd;
    private int orderIndex;
    private UUID leader;
    private long lastLeaderMsg;
    private boolean dirty;
    private long lastSave;

    private String creativeRule;      // "mark" | "disqualify" | "ignore"
    private DayOfWeek endDay;
    private int endHour;
    private ZoneId zone;
    private int minDays;
    private File stateFile;
    private MiniWeb web;
    private volatile String json = "{}";
    private byte[] page = new byte[0];

    /** Requests from the WebConsole dashboard (any thread). */
    private Map<String, Object> web(Map<String, Object> req) {
        return switch (String.valueOf(req.get("path"))) {
            case "/", "/index.html" -> HubLink.html(page);
            case "/api/data" -> HubLink.json(json);
            default -> HubLink.notFound();
        };
    }

    // ====================================================================== lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Msg.init("Challenge");
        creativeRule = getConfig().getString("creative-rule", "mark").toLowerCase(Locale.ROOT);
        try { endDay = DayOfWeek.valueOf(getConfig().getString("week.day", "MONDAY").toUpperCase(Locale.ROOT)); }
        catch (Exception e) { endDay = DayOfWeek.MONDAY; }
        endHour = getConfig().getInt("week.hour", 18);
        try { zone = ZoneId.of(getConfig().getString("week.timezone", "Europe/Berlin")); }
        catch (Exception e) { zone = ZoneId.systemDefault(); }
        minDays = getConfig().getInt("week.min-days-first-week", 3);

        ConfigurationSection sec = getConfig().getConfigurationSection("challenges");
        if (sec != null) for (String id : sec.getKeys(false)) {
            ConfigurationSection cs = sec.getConfigurationSection(id);
            if (cs == null || !cs.getBoolean("enabled", true)) continue;
            Challenge c = Challenge.from(id, cs, getLogger());
            if (c != null) challenges.put(id, c);
        }
        if (challenges.isEmpty()) {
            getLogger().severe("Keine gültigen Challenges in der config.yml – Plugin pausiert.");
            return;
        }

        stateFile = new File(getDataFolder(), "state.json");
        loadState();
        if (current == null) startNext(false);

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("challenge") != null) getCommand("challenge").setExecutor(this);

        long period = Math.max(2, getConfig().getInt("poll-seconds", 5)) * 20L;
        getServer().getScheduler().runTaskTimer(this, this::tick, 40L, period);
        rebuild();

        try (InputStream in = getResource("web/index.html")) {
            page = in == null ? new byte[0] : in.readAllBytes();
        } catch (Exception e) {
            page = "Fehler".getBytes(StandardCharsets.UTF_8);
        }
        HubLink.register(this, "challenge", "Challenge", 30, "member", this::web);
        if (!HubLink.ownServer(this)) {
            getLogger().info("Challenge-Seite läuft im Dashboard der WebConsole.");
            return;
        }
        web = new MiniWeb(getLogger(), getConfig().getString("web.bind", "0.0.0.0"), getConfig().getInt("web.port", 8767),
                getConfig().getString("web.password", ""), page, () -> json);
        try {
            web.start();
            getLogger().info("Challenge-Seite: http://<server-ip>:" + getConfig().getInt("web.port", 8767) + "/");
        } catch (Exception e) {
            getLogger().severe("Webserver konnte nicht starten: " + e.getMessage());
            web = null;
        }
    }

    @Override
    public void onDisable() {
        if (current != null) {
            for (Player p : getServer().getOnlinePlayers()) update(p);
            save();
        }
        if (web != null) web.stop();
    }

    private void tick() {
        if (System.currentTimeMillis() >= weekEnd) {
            for (Player p : getServer().getOnlinePlayers()) update(p);
            finishWeek();
            startNext(true);
        }
        for (Player p : getServer().getOnlinePlayers()) update(p);
        checkLeader();
        rebuild();
        if (dirty && System.currentTimeMillis() - lastSave > 60_000) save();
    }

    // ====================================================================== counting

    private Entry entry(Player p) {
        Entry e = progress.computeIfAbsent(p.getUniqueId(), k -> new Entry());
        e.name = p.getName();
        return e;
    }

    private static boolean countableMode(GameMode gm) {
        return gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE;
    }

    /**
     * Reads the player's vanilla statistics and adds whatever grew since the last read.
     * Growth only counts if the player was in survival/adventure at this and the previous read,
     * so nothing done in creative (or right around a gamemode switch) ends up in the ranking.
     */
    private void update(Player p) {
        if (current == null) return;
        UUID id = p.getUniqueId();
        GameMode gm = p.getGameMode();
        boolean countable = countableMode(gm);
        boolean prev = lastCountable.getOrDefault(id, countable);
        lastCountable.put(id, countable);

        if (gm == GameMode.CREATIVE && !creativeRule.equals("ignore")) {
            Entry e = entry(p);
            if (!e.creative) { e.creative = true; dirty = true; }
        }

        Map<String, Integer> base = baseline.computeIfAbsent(id, k -> new HashMap<>());
        long gain = 0;
        for (StatRef r : current.refs()) {
            int now = r.read(p);
            Integer before = base.put(r.key(), now);
            if (before == null) continue;
            int d = now - before;
            if (d > 0 && countable && prev) gain += d;
        }
        if (gain <= 0) return;

        Entry e = entry(p);
        if (e.creative && creativeRule.equals("disqualify")) return;
        e.raw += gain;
        dirty = true;

        if (current.race() && e.finishedAt == 0 && e.raw / current.divisor() >= current.target()) {
            e.finishedAt = System.currentTimeMillis();
            long place = progress.values().stream().filter(x -> x.finishedAt > 0).count();
            broadcast(Msg.prefix() + Msg.name(p.getName()) + Msg.OK + " hat es geschafft" + (place == 1 ? " – als Erste(r)!" : " – Platz " + place + "!"));
        }
    }

    private void checkLeader() {
        if (current == null || current.race()) return;
        List<Map.Entry<UUID, Entry>> r = ranked();
        if (r.isEmpty() || r.get(0).getValue().raw <= 0) return;
        UUID top = r.get(0).getKey();
        if (top.equals(leader)) return;
        boolean first = leader == null;
        leader = top;
        long now = System.currentTimeMillis();
        if (!first && now - lastLeaderMsg > 120_000 && getConfig().getBoolean("announce-new-leader", true)) {
            lastLeaderMsg = now;
            Entry e = r.get(0).getValue();
            broadcast(Msg.prefix() + Msg.name(e.name) + " übernimmt die Führung mit " + Msg.val(current.fmt(e.raw)) + "!");
        }
    }

    private boolean eligible(Entry e) {
        return !(e.creative && creativeRule.equals("disqualify"));
    }

    /** Ranking of the running week: race = finished first (by time), then by value. */
    private List<Map.Entry<UUID, Entry>> ranked() {
        List<Map.Entry<UUID, Entry>> l = new ArrayList<>();
        for (Map.Entry<UUID, Entry> e : progress.entrySet()) if (eligible(e.getValue())) l.add(e);
        Comparator<Map.Entry<UUID, Entry>> byValue = Comparator.comparingLong(e -> -e.getValue().raw);
        if (current != null && current.race()) {
            l.sort(Comparator.<Map.Entry<UUID, Entry>>comparingLong(e ->
                    e.getValue().finishedAt == 0 ? Long.MAX_VALUE : e.getValue().finishedAt).thenComparing(byValue));
        } else {
            l.sort(byValue);
        }
        return l;
    }

    // ====================================================================== week change

    private void finishWeek() {
        if (current == null) return;
        List<Map.Entry<UUID, Entry>> r = ranked();
        List<Map<String, Object>> results = new ArrayList<>();
        int place = 0;
        List<String> winners = new ArrayList<>();
        for (Map.Entry<UUID, Entry> e : r) {
            Entry en = e.getValue();
            if (en.raw <= 0) continue;
            place++;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("uuid", e.getKey().toString());
            m.put("name", en.name);
            m.put("raw", en.raw);
            m.put("value", current.display(en.raw));
            if (en.creative) m.put("creative", true);
            if (en.finishedAt > 0) m.put("finishedAt", en.finishedAt);
            results.add(m);
            // in a race only those who reached the target get prizes
            boolean prizeWorthy = !current.race() || en.finishedAt > 0;
            if (place <= 3 && prizeWorthy) {
                reward(e.getKey(), en.name, place);
                winners.add("§6" + place + ". " + Msg.name(en.name) + " " + Msg.val(current.fmt(en.raw)));
            }
            if (place >= 20) break;
        }
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("id", current.id());
        h.put("title", current.title());
        h.put("description", current.description());
        h.put("mode", current.race() ? "race" : "most");
        h.put("unit", current.unit());
        h.put("decimals", current.decimals());
        h.put("start", weekStart);
        h.put("end", System.currentTimeMillis());
        h.put("results", results);
        history.add(h);

        broadcast(Msg.prefix() + "Die Woche ist vorbei: " + Msg.HEAD + current.title());
        if (winners.isEmpty()) broadcast("  §7Diesmal hat niemand gepunktet.");
        else for (String w : winners) broadcast("  " + w);
        dirty = true;
    }

    private void startNext(boolean announce) {
        List<String> ids = new ArrayList<>(challenges.keySet());
        String next;
        if ("order".equalsIgnoreCase(getConfig().getString("selection", "random"))) {
            next = ids.get(Math.floorMod(orderIndex++, ids.size()));
        } else {
            // avoid the last few challenges so it doesn't repeat too quickly
            List<String> pool = new ArrayList<>(ids);
            int avoid = Math.min(pool.size() - 1, Math.min(4, history.size()));
            for (int i = 0; i < avoid; i++) pool.remove(String.valueOf(history.get(history.size() - 1 - i).get("id")));
            if (pool.isEmpty()) pool = ids;
            next = pool.get(random.nextInt(pool.size()));
        }
        setChallenge(challenges.get(next));
        if (announce) announceCurrent();
    }

    private void setChallenge(Challenge c) {
        current = c;
        weekStart = System.currentTimeMillis();
        weekEnd = WeekCalc.nextEnd(weekStart, endDay, endHour, zone, minDays);
        progress.clear();
        baseline.clear();
        lastCountable.clear();
        leader = null;
        dirty = true;
        save();
    }

    private void announceCurrent() {
        broadcast(Msg.prefix() + "Neue Wochen-Challenge: " + Msg.HEAD + "§l" + current.title());
        if (!current.description().isEmpty()) broadcast("  §7" + current.description());
        broadcast("  §7Läuft bis " + Msg.val(endText()) + ". Infos mit " + Msg.cmd("/challenge"));
    }

    // ====================================================================== rewards

    private void reward(UUID id, String name, int place) {
        List<String> specs = getConfig().getStringList("rewards." + place);
        if (specs.isEmpty()) return;
        Player p = getServer().getPlayer(id);
        if (p != null && p.isOnline()) {
            for (String s : specs) give(p, s);
            Msg.ok(p, "Du hast Platz " + place + " belegt – deine Belohnung ist im Inventar!");
        } else {
            pendingRewards.computeIfAbsent(id.toString(), k -> new ArrayList<>()).addAll(specs);
        }
    }

    private void give(Player p, String spec) {
        String s = spec.trim();
        if (s.startsWith("cmd:")) {
            getServer().dispatchCommand(getServer().getConsoleSender(), s.substring(4).trim().replace("%player%", p.getName()));
            return;
        }
        String[] parts = s.split(":");
        Material m = Material.matchMaterial(parts[0]);
        if (m == null) { getLogger().warning("Unbekanntes Belohnungs-Item: " + s); return; }
        int amount = 1;
        try { if (parts.length > 1) amount = Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) { }
        ItemStack it = new ItemStack(m, Math.max(1, amount));
        for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
    }

    // ====================================================================== events

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent ev) {
        Player p = ev.getPlayer();
        baseline.remove(p.getUniqueId());
        lastCountable.remove(p.getUniqueId());
        List<String> pend = pendingRewards.remove(p.getUniqueId().toString());
        getServer().getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            if (pend != null && !pend.isEmpty()) {
                for (String s : pend) give(p, s);
                Msg.ok(p, "Du hast in der letzten Challenge gewonnen – hier ist deine Belohnung!");
                dirty = true;
            }
            if (current != null) {
                Entry e = progress.get(p.getUniqueId());
                Msg.info(p, "Diese Woche: " + Msg.HEAD + current.title() + Msg.TEXT + " (bis " + endText() + ")");
                Msg.kv(p, "Dein Stand", (e == null ? current.fmt(0) : current.fmt(e.raw)) + rankText(p.getUniqueId()));
            }
        }, 60L);
        if (pend != null) dirty = true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent ev) {
        update(ev.getPlayer());
        baseline.remove(ev.getPlayer().getUniqueId());
        lastCountable.remove(ev.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent ev) {
        // bank everything gained so far in the old mode, then make sure the next read doesn't count
        Player p = ev.getPlayer();
        update(p);
        lastCountable.put(p.getUniqueId(), false);
        if (ev.getNewGameMode() == GameMode.CREATIVE && !creativeRule.equals("ignore") && current != null) {
            Entry e = entry(p);
            if (!e.creative) {
                e.creative = true;
                dirty = true;
                if (creativeRule.equals("disqualify"))
                    Msg.err(p, "Du warst im Creative – für diese Woche zählst du in der Challenge nicht mehr.");
            }
        }
    }

    // ====================================================================== command

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (current == null) { Msg.err(sender, "Gerade läuft keine Challenge."); return true; }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        boolean console = sender instanceof ConsoleCommandSender;

        if (sub.equals("next") || sub.equals("set") || sub.equals("list")) {
            if (!console) { Msg.err(sender, "Das geht nur über die Server-Konsole."); return true; }
            switch (sub) {
                case "list" -> Msg.info(sender, "Challenges: " + Msg.val(String.join(", ", challenges.keySet())));
                case "next" -> {
                    for (Player p : getServer().getOnlinePlayers()) update(p);
                    finishWeek();
                    startNext(true);
                    Msg.ok(sender, "Woche beendet, neue Challenge: " + current.title());
                }
                default -> {
                    Challenge c = args.length > 1 ? challenges.get(args[1]) : null;
                    if (c == null) { Msg.err(sender, "Nutzung: " + Msg.cmd("challenge set <id>") + Msg.ERR + " – IDs zeigt " + Msg.cmd("challenge list")); return true; }
                    setChallenge(c);
                    announceCurrent();
                    Msg.ok(sender, "Challenge gewechselt (ohne Wertung): " + c.title());
                }
            }
            rebuild();
            return true;
        }

        if (sender instanceof Player p) update(p);
        Msg.head(sender, current.title());
        if (!current.description().isEmpty()) Msg.line(sender, current.description());
        Msg.kv(sender, "Endet", endText());
        List<Map.Entry<UUID, Entry>> r = ranked();
        int i = 0;
        for (Map.Entry<UUID, Entry> e : r) {
            if (e.getValue().raw <= 0 || i >= 5) break;
            i++;
            Entry en = e.getValue();
            String flag = en.creative && creativeRule.equals("mark") ? " §c(war im Creative)" : "";
            String done = current.race() && en.finishedAt > 0 ? " §a✔" : "";
            Msg.send(sender, "  §6" + i + ". " + Msg.name(en.name) + " – " + Msg.val(current.fmt(en.raw)) + done + flag);
        }
        if (i == 0) Msg.line(sender, "Noch hat niemand gepunktet.");
        if (sender instanceof Player p) {
            Entry e = progress.get(p.getUniqueId());
            Msg.kv(sender, "Du", (e == null ? current.fmt(0) : current.fmt(e.raw)) + rankText(p.getUniqueId()));
        }
        String url = getConfig().getString("web.public-url", "");
        if (url != null && !url.isBlank()) Msg.line(sender, "Alles auf " + Msg.CMD + url);
        return true;
    }

    private String rankText(UUID id) {
        List<Map.Entry<UUID, Entry>> r = ranked();
        for (int i = 0; i < r.size(); i++)
            if (r.get(i).getKey().equals(id) && r.get(i).getValue().raw > 0) return " §7(Platz " + (i + 1) + ")";
        return "";
    }

    private String endText() {
        return java.time.Instant.ofEpochMilli(weekEnd).atZone(zone)
                .format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d. MMMM HH:mm 'Uhr'", Locale.GERMAN));
    }

    private void broadcast(String msg) {
        for (Player p : getServer().getOnlinePlayers()) Msg.send(p, msg);
        getLogger().info(msg.replaceAll("§.", "").trim());
    }

    // ====================================================================== web data

    private void rebuild() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("serverName", getConfig().getString("server-name", "Unser Server"));
        out.put("now", System.currentTimeMillis());
        out.put("creativeRule", creativeRule);
        out.put("poolSize", challenges.size());
        out.put("selection", getConfig().getString("selection", "random"));
        if (current != null) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", current.id());
            c.put("title", current.title());
            c.put("description", current.description());
            c.put("mode", current.race() ? "race" : "most");
            c.put("unit", current.unit());
            c.put("decimals", current.decimals());
            if (current.race()) c.put("target", current.target());
            c.put("start", weekStart);
            c.put("end", weekEnd);
            out.put("challenge", c);
            List<Map<String, Object>> rank = new ArrayList<>();
            for (Map.Entry<UUID, Entry> e : ranked()) {
                Entry en = e.getValue();
                if (en.raw <= 0) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("uuid", e.getKey().toString());
                m.put("name", en.name);
                m.put("value", current.display(en.raw));
                if (en.creative) m.put("creative", true);
                if (en.finishedAt > 0) m.put("finishedAt", en.finishedAt);
                Player on = getServer().getPlayer(e.getKey());
                m.put("online", on != null && on.isOnline());
                rank.add(m);
            }
            out.put("ranking", rank);
        }
        Map<String, Object> rewards = new LinkedHashMap<>();
        for (int i = 1; i <= 3; i++) {
            List<String> pretty = new ArrayList<>();
            boolean bonus = false;
            for (String s : getConfig().getStringList("rewards." + i)) {
                if (s.trim().startsWith("cmd:")) { bonus = true; continue; }
                String[] p = s.trim().split(":");
                pretty.add((p.length > 1 ? p[1] : "1") + "× " + p[0].toLowerCase(Locale.ROOT));
            }
            String text = getConfig().getString("rewards-text." + i, "");
            if (text != null && !text.isBlank()) pretty.add(text);
            else if (bonus) pretty.add("+ Überraschung");
            rewards.put(String.valueOf(i), pretty);
        }
        out.put("rewards", rewards);
        out.put("history", history);
        json = Json.write(out);
    }

    // ====================================================================== persistence

    private void save() {
        if (current == null) return;
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("current", current.id());
        s.put("weekStart", weekStart);
        s.put("weekEnd", weekEnd);
        s.put("orderIndex", orderIndex);
        Map<String, Object> pr = new LinkedHashMap<>();
        for (Map.Entry<UUID, Entry> e : progress.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.getValue().name);
            m.put("raw", e.getValue().raw);
            m.put("creative", e.getValue().creative);
            m.put("finishedAt", e.getValue().finishedAt);
            pr.put(e.getKey().toString(), m);
        }
        s.put("progress", pr);
        s.put("pendingRewards", pendingRewards);
        s.put("history", history);
        try {
            getDataFolder().mkdirs();
            File tmp = new File(getDataFolder(), "state.json.tmp");
            Files.writeString(tmp.toPath(), Json.write(s), StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), stateFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
            lastSave = System.currentTimeMillis();
        } catch (Exception e) {
            getLogger().warning("Speichern fehlgeschlagen: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void loadState() {
        if (!stateFile.isFile()) return;
        try {
            Map<String, Object> s = (Map<String, Object>) Json.parse(Files.readString(stateFile.toPath(), StandardCharsets.UTF_8));
            Object h = s.get("history");
            if (h instanceof List<?> l) for (Object o : l) history.add((Map<String, Object>) o);
            Object pend = s.get("pendingRewards");
            if (pend instanceof Map<?, ?> pm) for (Map.Entry<?, ?> e : pm.entrySet()) {
                List<String> l = new ArrayList<>();
                for (Object o : (List<Object>) e.getValue()) l.add(String.valueOf(o));
                pendingRewards.put(String.valueOf(e.getKey()), l);
            }
            orderIndex = ((Number) s.getOrDefault("orderIndex", 0L)).intValue();
            Challenge c = challenges.get(String.valueOf(s.get("current")));
            if (c == null) return; // challenge was removed from the config -> a new one starts
            current = c;
            weekStart = ((Number) s.get("weekStart")).longValue();
            weekEnd = ((Number) s.get("weekEnd")).longValue();
            Object pr = s.get("progress");
            if (pr instanceof Map<?, ?> pm) for (Map.Entry<?, ?> e : pm.entrySet()) {
                Map<String, Object> m = (Map<String, Object>) e.getValue();
                Entry en = new Entry();
                en.name = String.valueOf(m.get("name"));
                en.raw = ((Number) m.getOrDefault("raw", 0L)).longValue();
                en.creative = Boolean.TRUE.equals(m.get("creative"));
                en.finishedAt = ((Number) m.getOrDefault("finishedAt", 0L)).longValue();
                progress.put(UUID.fromString(String.valueOf(e.getKey())), en);
            }
        } catch (Exception e) {
            getLogger().warning("state.json konnte nicht gelesen werden (" + e.getMessage() + ") – starte neu.");
        }
    }
}
