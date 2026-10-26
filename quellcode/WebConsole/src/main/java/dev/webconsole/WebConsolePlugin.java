package dev.webconsole;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class WebConsolePlugin extends JavaPlugin implements Backend, CommandExecutor {

    private Users users;
    private Sessions sessions;
    private LogTail tail;
    private WebApi web;
    private final ArrayDeque<Double> tpsHistory = new ArrayDeque<>();
    private volatile Map<String, Object> status = Map.of();
    private List<Map<String, Object>> menu = List.of();
    private boolean webDispatch;
    private final ExecutorService auditIo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WebConsole-Audit");
        t.setDaemon(true);
        return t;
    });

    @Override
    public void onEnable() {
        saveDefaultConfig();

        users = new Users(new File(getDataFolder(), "users.json"), getLogger());
        users.load();
        if (users.isEmpty()) {
            String pw = users.create("admin", Users.ADMIN);
            secret("============================================================");
            secret("Web-Konsole: erstes Konto angelegt");
            secret("  Benutzer: admin");
            secret("  Passwort: " + pw);
            secret("Ändere es nach dem ersten Login unter „Passwort ändern“.");
            secret("============================================================");
        }
        sessions = new Sessions(new File(getDataFolder(), "sessions.json"), getConfig().getInt("session-days", 14), getLogger());
        sessions.load();

        tail = new LogTail(new File("logs", "latest.log").getAbsoluteFile(),
                getConfig().getInt("log-lines", 3000),
                getConfig().getStringList("hide-lines-containing"),
                getLogger());
        tail.start();

        menu = loadMenu();
        gather();
        getServer().getScheduler().runTaskTimer(this, this::gather, 40L, 40L);
        if (getCommand("webconsole") != null) getCommand("webconsole").setExecutor(this);

        byte[] page;
        try (InputStream in = getResource("web/index.html")) {
            page = in == null ? new byte[0] : in.readAllBytes();
        } catch (Exception e) {
            page = "Fehler".getBytes(StandardCharsets.UTF_8);
        }
        int port = getConfig().getInt("web.port", 8768);
        web = new WebApi(this, users, sessions, tail, page, getConfig().getString("web.bind", "0.0.0.0"), port,
                getConfig().getBoolean("mask-ips-for-members", true), getLogger());
        try {
            web.start();
            getLogger().info("Web-Konsole läuft auf http://<server-ip>:" + port + "/");
        } catch (Exception e) {
            getLogger().severe("Webserver konnte nicht starten: " + e.getMessage());
            web = null;
        }
    }

    @Override
    public void onDisable() {
        if (web != null) web.stop();
        if (tail != null) tail.stop();
        auditIo.shutdown();
        try { auditIo.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
    }

    /** Printed to the real console only – the web console hides these lines from everyone. */
    private void secret(String msg) {
        getLogger().info(LogTail.PRIVATE + " " + msg);
    }

    // ====================================================================== Backend

    @Override
    public Map<String, Object> status() {
        return status;
    }

    @Override
    public List<Map<String, Object>> modules() {
        return Modules.find(getServer().getServicesManager(), getConfig());
    }

    /** Reads "command-menu" (and the older "quick-commands") from the config. */
    private List<Map<String, Object>> loadMenu() {
        List<Map<String, Object>> menu = new ArrayList<>();
        for (Map<?, ?> g : getConfig().getMapList("command-menu")) {
            if (!(g.get("items") instanceof List<?> items)) continue;
            List<Map<String, Object>> its = new ArrayList<>();
            for (Object o : items) {
                if (!(o instanceof Map<?, ?> im) || im.get("command") == null) continue;
                String command = String.valueOf(im.get("command")).strip();
                while (command.startsWith("/")) command = command.substring(1);
                Map<String, Object> it = new LinkedHashMap<>();
                it.put("label", im.get("label") == null ? command : String.valueOf(im.get("label")));
                it.put("command", command);
                if (Boolean.TRUE.equals(im.get("confirm"))) it.put("confirm", true);
                its.add(it);
            }
            if (its.isEmpty()) continue;
            Map<String, Object> grp = new LinkedHashMap<>();
            grp.put("group", g.get("group") == null ? "Befehle" : String.valueOf(g.get("group")));
            grp.put("items", its);
            menu.add(grp);
        }
        List<String> quick = getConfig().getStringList("quick-commands");
        if (!quick.isEmpty()) {
            List<Map<String, Object>> its = new ArrayList<>();
            for (String q : quick) its.add(Map.of("label", q, "command", q));
            menu.add(0, Map.of("group", "Schnellbefehle", "items", its));
        }
        return menu;
    }

    @Override
    public void runCommand(String user, String ip, String command) {
        audit(user, ip, "/" + command, true);
        getServer().getScheduler().runTask(this, () -> {
            webDispatch = true;
            try {
                getServer().dispatchCommand(getServer().getConsoleSender(), command);
            } catch (Exception e) {
                getLogger().warning("Befehl fehlgeschlagen: " + e.getMessage());
            } finally {
                webDispatch = false;
            }
        });
    }

    @Override
    public void audit(String user, String ip, String text, boolean toConsole) {
        if (toConsole) getLogger().info("[Web] " + user + ": " + text);
        String line = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                + "  " + ip + "  " + user + "  " + text + System.lineSeparator();
        File f = new File(getDataFolder(), "audit.log");
        auditIo.execute(() -> {
            try (BufferedWriter w = Files.newBufferedWriter(f.toPath(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(line);
            } catch (Exception ignored) { }
        });
    }

    /** Main thread: collect everything the status panel shows. */
    private void gather() {
        double tps = 20, mspt = 0;
        try {
            tps = getServer().getTPS()[0];
            mspt = getServer().getAverageTickTime();
        } catch (Throwable ignored) { }
        tps = Math.min(20.0, Math.round(tps * 10) / 10.0);
        tpsHistory.addLast(tps);
        while (tpsHistory.size() > 150) tpsHistory.removeFirst(); // 150 × 2 s = 5 min

        List<Map<String, Object>> players = new ArrayList<>();
        for (Player p : getServer().getOnlinePlayers()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.getName());
            m.put("world", p.getWorld().getName());
            m.put("gm", p.getGameMode().name().toLowerCase(Locale.ROOT));
            m.put("ping", p.getPing());
            players.add(m);
        }
        players.sort((a, b) -> String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name"))));

        Runtime rt = Runtime.getRuntime();
        String version;
        try { version = getServer().getMinecraftVersion(); } catch (Throwable t) { version = getServer().getVersion(); }

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("serverName", getConfig().getString("server-name", "Unser Server"));
        s.put("tps", tps);
        s.put("mspt", Math.round(mspt * 10) / 10.0);
        s.put("tpsHistory", new ArrayList<>(tpsHistory));
        s.put("memUsed", rt.totalMemory() - rt.freeMemory());
        s.put("memMax", rt.maxMemory());
        s.put("players", players);
        s.put("maxPlayers", getServer().getMaxPlayers());
        s.put("version", version);
        s.put("uptime", ManagementFactory.getRuntimeMXBean().getUptime());
        s.put("menu", menu);
        status = s;
    }

    // ====================================================================== /webconsole (console only)

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (webDispatch) {
            sender.sendMessage("Konten bitte im Tab „Konten“ der Web-Konsole verwalten.");
            return true;
        }
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage("§cDas geht nur über die Server-Konsole.");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        String name = args.length > 1 ? args[1] : null;
        String role = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : null;
        switch (sub) {
            case "list" -> {
                sender.sendMessage("Konten der Web-Konsole:");
                for (Map<String, Object> u : users.list()) sender.sendMessage("  " + u.get("name") + "  (" + u.get("role") + ")");
            }
            case "add", "reset" -> {
                if (name == null || (sub.equals("add") && role == null)) { usage(sender); return true; }
                // hashing takes a moment – keep it off the main thread
                getServer().getScheduler().runTaskAsynchronously(this, () -> {
                    try {
                        String pw;
                        if (sub.equals("add")) pw = users.create(name, role);
                        else {
                            if (users.role(name) == null) throw new IllegalArgumentException("Das Konto gibt es nicht.");
                            pw = users.resetPassword(name);
                            sessions.invalidateUser(name);
                        }
                        secret("Web-Konsole: " + users.displayName(name) + " – Passwort: " + pw);
                        audit("Konsole", "-", (sub.equals("add") ? "Konto angelegt: " : "Passwort zurückgesetzt: ") + name, false);
                    } catch (IllegalArgumentException e) {
                        getLogger().warning(e.getMessage());
                    }
                });
            }
            case "role" -> {
                if (name == null || role == null) { usage(sender); return true; }
                try {
                    users.setRole(name, role);
                    sender.sendMessage(users.displayName(name) + " ist jetzt " + role + ".");
                } catch (IllegalArgumentException e) { sender.sendMessage(e.getMessage()); }
            }
            case "remove" -> {
                if (name == null) { usage(sender); return true; }
                try {
                    users.delete(name);
                    sessions.invalidateUser(name);
                    sender.sendMessage("Konto " + name + " gelöscht.");
                } catch (IllegalArgumentException e) { sender.sendMessage(e.getMessage()); }
            }
            default -> usage(sender);
        }
        return true;
    }

    private static void usage(CommandSender s) {
        s.sendMessage("webconsole list");
        s.sendMessage("webconsole add <name> <admin|member>   – legt ein Konto an, Passwort steht danach in der Konsole");
        s.sendMessage("webconsole reset <name>                – neues Passwort");
        s.sendMessage("webconsole role <name> <admin|member>");
        s.sendMessage("webconsole remove <name>");
    }
}
