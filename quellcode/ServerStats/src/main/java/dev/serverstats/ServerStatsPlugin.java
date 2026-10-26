package dev.serverstats;

import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ServerStatsPlugin extends JavaPlugin implements Listener, CommandExecutor {

    /** key, label, unit, divisor, decimals, statistics that get summed */
    private record Def(String key, String label, String unit, double div, int dec, List<StatRef> refs) {}

    private static final String[][] DEFS = {
            {"playtime", "Spielzeit", "h", "72000", "1", "PLAY_ONE_MINUTE"},
            {"deaths", "Tode", "", "1", "0", "DEATHS"},
            {"mob_kills", "Mobs getötet", "", "1", "0", "MOB_KILLS"},
            {"player_kills", "Spieler getötet", "", "1", "0", "PLAYER_KILLS"},
            {"diamonds", "Diamanterz abgebaut", "", "1", "0", "MINE_BLOCK:DIAMOND_ORE,MINE_BLOCK:DEEPSLATE_DIAMOND_ORE"},
            {"debris", "Antike Trümmer", "", "1", "0", "MINE_BLOCK:ANCIENT_DEBRIS"},
            {"walked", "Zu Fuß", "km", "100000", "1", "WALK_ONE_CM,SPRINT_ONE_CM,CROUCH_ONE_CM"},
            {"traveled", "Insgesamt unterwegs", "km", "100000", "1",
                    "WALK_ONE_CM,SPRINT_ONE_CM,CROUCH_ONE_CM,SWIM_ONE_CM,WALK_ON_WATER_ONE_CM,WALK_UNDER_WATER_ONE_CM,"
                            + "CLIMB_ONE_CM,FALL_ONE_CM,BOAT_ONE_CM,HORSE_ONE_CM,PIG_ONE_CM,MINECART_ONE_CM,STRIDER_ONE_CM,AVIATE_ONE_CM"},
            {"elytra", "Mit Elytra geflogen", "km", "100000", "1", "AVIATE_ONE_CM"},
            {"jumps", "Sprünge", "", "1", "0", "JUMP"},
            {"fish", "Fische gefangen", "", "1", "0", "FISH_CAUGHT"},
            {"bred", "Tiere gezüchtet", "", "1", "0", "ANIMALS_BRED"},
            {"trades", "Dorfbewohner-Trades", "", "1", "0", "TRADED_WITH_VILLAGER"},
            {"damage_taken", "Schaden kassiert", "♥", "20", "0", "DAMAGE_TAKEN"},
            {"enchants", "Items verzaubert", "", "1", "0", "ITEM_ENCHANTED"},
            {"sleep", "Mal geschlafen", "", "1", "0", "SLEEP_IN_BED"},
            {"cake", "Kuchenstücke gegessen", "", "1", "0", "CAKE_SLICES_EATEN"},
            {"life", "Aktuelles Leben", "h", "72000", "1", "TIME_SINCE_DEATH"},
    };

    private static final String[] KILLERS = {
            "ZOMBIE", "HUSK", "DROWNED", "ZOMBIE_VILLAGER", "SKELETON", "STRAY", "BOGGED", "CREEPER", "SPIDER", "CAVE_SPIDER",
            "ENDERMAN", "WITCH", "PHANTOM", "PILLAGER", "VINDICATOR", "EVOKER", "RAVAGER", "VEX", "PIGLIN", "PIGLIN_BRUTE",
            "ZOMBIFIED_PIGLIN", "HOGLIN", "ZOGLIN", "BLAZE", "GHAST", "WITHER_SKELETON", "MAGMA_CUBE", "SLIME", "GUARDIAN",
            "ELDER_GUARDIAN", "WARDEN", "WITHER", "ENDER_DRAGON", "SHULKER", "SILVERFISH", "ENDERMITE", "BREEZE", "CREAKING",
            "BEE", "WOLF", "IRON_GOLEM", "POLAR_BEAR", "LLAMA", "GOAT", "PUFFERFISH", "DOLPHIN", "PANDA", "TNT"
    };

    private final List<Def> defs = new ArrayList<>();
    private final List<StatRef> killerRefs = new ArrayList<>();
    private final Map<UUID, Map<String, Object>> rows = new ConcurrentHashMap<>();
    private final Set<String> hidden = new HashSet<>();
    private DeathLog deaths;
    private MiniWeb web;
    private volatile String json = "{}";
    private byte[] page = new byte[0];
    private byte[] playersPage = new byte[0];
    private PlayerSnapshots snaps;

    /** Requests from the WebConsole dashboard (any thread). */
    private Map<String, Object> web(Map<String, Object> req) {
        return switch (String.valueOf(req.get("path"))) {
            case "/", "/index.html" -> HubLink.html(page);
            case "/api/data" -> HubLink.json(json);
            default -> HubLink.notFound();
        };
    }

    /** Tab "Spieler" in the WebConsole dashboard. Admins get location and inventories. */
    private Map<String, Object> playersWeb(Map<String, Object> req) {
        boolean admin = "admin".equals(req.get("role"));
        return switch (String.valueOf(req.get("path"))) {
            case "/", "/index.html" -> HubLink.html(playersPage);
            case "/api/players" -> HubLink.json(snaps.json(admin));
            default -> HubLink.notFound();
        };
    }

    /** Own web server: stats on "/", players on "/spieler/" (member view – there are no accounts here). */
    private Map<String, Object> standaloneRoute(String path) {
        return switch (path) {
            case "/", "/index.html" -> HubLink.html(page);
            case "/api/data" -> HubLink.json(json);
            case "/spieler/", "/spieler/index.html" -> HubLink.html(playersPage);
            case "/spieler/api/players" -> HubLink.json(snaps.json(false));
            default -> HubLink.notFound();
        };
    }

    private void loadPages() {
        try (InputStream in = getResource("web/index.html")) {
            page = in == null ? new byte[0] : in.readAllBytes();
        } catch (Exception e) {
            page = "Fehler".getBytes(StandardCharsets.UTF_8);
        }
        try (InputStream in = getResource("web/players.html")) {
            playersPage = in == null ? new byte[0] : in.readAllBytes();
        } catch (Exception e) {
            playersPage = "Fehler".getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (String s : getConfig().getStringList("hidden-players")) hidden.add(s.toLowerCase(Locale.ROOT));
        for (String[] d : DEFS) {
            List<StatRef> refs = new ArrayList<>();
            for (String spec : d[5].split(",")) {
                StatRef r = StatRef.parse(spec);
                if (r != null) refs.add(r);
            }
            if (!refs.isEmpty()) defs.add(new Def(d[0], d[1], d[2], Double.parseDouble(d[3]), Integer.parseInt(d[4]), refs));
        }
        for (String k : KILLERS) {
            StatRef r = StatRef.parse("ENTITY_KILLED_BY:" + k);
            if (r != null) killerRefs.add(r);
        }

        deaths = new DeathLog(new File(getDataFolder(), "deaths.jsonl"), getLogger());
        deaths.load();
        snaps = new PlayerSnapshots(new File(getDataFolder(), "players.json"), getLogger(),
                getConfig().getBoolean("players-page.members-see-location", false),
                getConfig().getBoolean("players-page.members-see-inventory", false));
        snaps.load();
        for (Player p : getServer().getOnlinePlayers()) snaps.joined(p);

        // all players who ever joined (vanilla stats go back to the very beginning of the world)
        for (OfflinePlayer p : getServer().getOfflinePlayers()) refresh(p, false);
        for (Player p : getServer().getOnlinePlayers()) refresh(p, true);
        rebuild();

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("stats") != null) getCommand("stats").setExecutor(this);

        long period = Math.max(10, getConfig().getInt("refresh-seconds", 30)) * 20L;
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player p : getServer().getOnlinePlayers()) refresh(p, true);
            rebuild();
        }, period, period);
        // live values (health, armor ...) change quickly – every 2 seconds
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player p : getServer().getOnlinePlayers()) snaps.capture(p);
            snaps.rebuild(rows, deaths.all(), hidden);
        }, 20L, 40L);
        snaps.rebuild(rows, deaths.all(), hidden);

        loadPages();
        HubLink.register(this, "stats", "Statistik", 20, "member", this::web);
        if (getConfig().getBoolean("players-page.enabled", true))
            HubLink.register(this, "players", "Spieler", 25, "member", this::playersWeb);
        if (!HubLink.ownServer(this)) {
            getLogger().info("Statistik-Seite läuft im Dashboard der WebConsole.");
            return;
        }
        web = new MiniWeb(getLogger(), getConfig().getString("web.bind", "0.0.0.0"), getConfig().getInt("web.port", 8766),
                getConfig().getString("web.password", ""), this::standaloneRoute);
        try {
            web.start();
            getLogger().info("Statistik-Seite: http://<server-ip>:" + getConfig().getInt("web.port", 8766) + "/");
        } catch (Exception e) {
            getLogger().severe("Webserver konnte nicht starten: " + e.getMessage());
            web = null;
        }
    }

    @Override
    public void onDisable() {
        if (snaps != null) snaps.shutdown(getServer().getOnlinePlayers());
        if (web != null) web.stop();
        if (deaths != null) deaths.close();
    }

    // ------------------------------------------------------------------ data

    private void refresh(OfflinePlayer p, boolean online) {
        String name = p.getName();
        if (name == null || hidden.contains(name.toLowerCase(Locale.ROOT))) return;
        if (!online && !p.hasPlayedBefore()) return;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("uuid", p.getUniqueId().toString());
        row.put("name", name);
        row.put("online", online);
        row.put("lastSeen", online ? System.currentTimeMillis() : p.getLastSeen());
        Map<String, Object> stats = new LinkedHashMap<>();
        for (Def d : defs) {
            long sum = 0;
            for (StatRef r : d.refs()) sum += r.read(p);
            double v = sum / d.div();
            double f = Math.pow(10, d.dec());
            stats.put(d.key(), Math.round(v * f) / f);
        }
        row.put("stats", stats);
        Map<String, Object> killers = new LinkedHashMap<>();
        for (StatRef r : killerRefs) {
            int n = r.read(p);
            if (n > 0) killers.put(r.ent().name().toLowerCase(Locale.ROOT), n);
        }
        row.put("killedBy", killers);
        rows.put(p.getUniqueId(), row);
    }

    private void rebuild() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("serverName", getConfig().getString("server-name", "Unser Server"));
        out.put("generated", System.currentTimeMillis());
        List<Map<String, Object>> dl = new ArrayList<>();
        for (Def d : defs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", d.key());
            m.put("label", d.label());
            m.put("unit", d.unit());
            m.put("dec", d.dec());
            dl.add(m);
        }
        out.put("defs", dl);
        out.put("players", new ArrayList<>(rows.values()));
        List<Map<String, Object>> all = deaths.all();
        List<Map<String, Object>> visible = new ArrayList<>();
        for (Map<String, Object> d : all) {
            Object n = d.get("name");
            if (n == null || !hidden.contains(n.toString().toLowerCase(Locale.ROOT))) visible.add(d);
        }
        out.put("deaths", visible.size() > 5000 ? visible.subList(visible.size() - 5000, visible.size()) : visible);
        json = Json.write(out);
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent ev) {
        Player p = ev.getEntity();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("ts", System.currentTimeMillis());
        d.put("uuid", p.getUniqueId().toString());
        d.put("name", p.getName());
        d.put("world", p.getWorld().getName());

        String cause = "UNKNOWN";
        String by = null;
        EntityDamageEvent last = p.getLastDamageCause();
        if (last != null) {
            cause = last.getCause().name();
            if (last instanceof EntityDamageByEntityEvent ee) {
                Entity damager = ee.getDamager();
                if (damager instanceof Projectile proj) {
                    ProjectileSource src = proj.getShooter();
                    if (src instanceof Entity se) damager = se;
                }
                if (damager instanceof Player killer) by = killer.getName();
                else if (damager != null) by = damager.getType().name().toLowerCase(Locale.ROOT);
                if (damager instanceof Player) cause = "PVP";
            }
        }
        Player killer = p.getKiller();
        if (killer != null && !killer.equals(p)) { cause = "PVP"; by = killer.getName(); }
        d.put("cause", cause);
        if (by != null) d.put("by", by);
        deaths.add(d);
        getServer().getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) { refresh(p, true); snaps.capture(p); }
            rebuild();
            snaps.rebuild(rows, deaths.all(), hidden);
        }, 1L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent ev) {
        refresh(ev.getPlayer(), true);
        rebuild();
        snaps.joined(ev.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent ev) {
        refresh(ev.getPlayer(), false);
        Map<String, Object> row = rows.get(ev.getPlayer().getUniqueId());
        if (row != null) row.put("lastSeen", System.currentTimeMillis());
        rebuild();
        snaps.left(ev.getPlayer());
        snaps.rebuild(rows, deaths.all(), hidden);
        snaps.saveAsync();
    }

    // ------------------------------------------------------------------ /stats

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        OfflinePlayer target = null;
        if (args.length > 0) {
            for (OfflinePlayer o : getServer().getOfflinePlayers())
                if (o.getName() != null && o.getName().equalsIgnoreCase(args[0])) target = o;
            Player on = getServer().getPlayerExact(args[0]);
            if (on != null) target = on;
        } else if (sender instanceof Player p) {
            target = p;
        }
        if (target == null) {
            sender.sendMessage("§cSpieler nicht gefunden. Nutzung: /stats [Spieler]");
            return true;
        }
        refresh(target, target.isOnline());
        Map<String, Object> row = rows.get(target.getUniqueId());
        if (row == null) {
            sender.sendMessage("§cKeine Daten.");
            return true;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> st = (Map<String, Object>) row.get("stats");
        sender.sendMessage("§6§l" + row.get("name") + " §7– Statistik");
        String[] show = {"playtime", "deaths", "mob_kills", "diamonds", "walked", "fish"};
        for (String k : show) {
            for (Def d : defs) {
                if (!d.key().equals(k)) continue;
                Object v = st.get(k);
                String val = d.dec() == 0 ? String.valueOf(Math.round(((Number) v).doubleValue())) : String.valueOf(v);
                sender.sendMessage("§7" + d.label() + ": §f" + val + (d.unit().isEmpty() ? "" : " " + d.unit()));
            }
        }
        String url = getConfig().getString("web.public-url", "");
        if (url != null && !url.isBlank()) sender.sendMessage("§7Mehr auf §b" + url);
        return true;
    }
}
