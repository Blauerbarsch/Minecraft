package dev.opwatch;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.security.SecureRandom;

public final class OpWatchPlugin extends JavaPlugin {

    private EventStore store;
    private WebServer web;
    private CreativeTracker tracker;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensurePassword();

        CommandCategorizer categorizer = new CommandCategorizer(getConfig());
        store = new EventStore(
                new File(getDataFolder(), "logs"),
                getConfig().getInt("storage.memory-events", 20000),
                getConfig().getInt("storage.load-days", 7),
                getLogger());
        store.loadHistory();

        tracker = new CreativeTracker(this, store, getConfig());
        getServer().getPluginManager().registerEvents(new MonitorListener(this, store, categorizer, tracker), this);
        tracker.initOnlinePlayers();

        web = new WebServer(this, store, categorizer,
                getConfig().getString("web.bind", "0.0.0.0"),
                getConfig().getInt("web.port", 8765),
                getConfig().getString("web.username", "admin"),
                getConfig().getString("web.password", ""));
        try {
            web.startData();
        } catch (Exception e) {
            getLogger().severe("Dashboard-Seite konnte nicht geladen werden: " + e.getMessage());
        }
        HubLink.register(this, "opwatch", "Überwachung", 40, "admin", web::hub);

        if (getConfig().getBoolean("web.enabled", true) && HubLink.ownServer(this)) {
            try {
                web.startHttp();
                getLogger().info("Dashboard läuft auf http://" + getConfig().getString("web.bind", "0.0.0.0")
                        + ":" + getConfig().getInt("web.port", 8765) + "/  (Login steht in plugins/OpWatch/config.yml)");
            } catch (Exception e) {
                getLogger().severe("Webserver konnte nicht starten: " + e.getMessage());
            }
        } else if (HubLink.hubInstalled(this)) {
            getLogger().info("Überwachung läuft im Dashboard der WebConsole (nur für Admins).");
        }
    }

    @Override
    public void onDisable() {
        if (tracker != null) tracker.flushAll();
        if (web != null) web.stop();
        if (store != null) store.close();
    }

    /** Generates a random dashboard password on first start so the panel is never open by default. */
    private void ensurePassword() {
        String pw = getConfig().getString("web.password", "");
        if (pw == null || pw.isBlank() || pw.equals("CHANGE_ME")) {
            String chars = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
            SecureRandom rnd = new SecureRandom();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(chars.charAt(rnd.nextInt(chars.length())));
            getConfig().set("web.password", sb.toString());
            saveConfig();
            // "[nur Konsole]" keeps the line out of the WebConsole log view
            getLogger().warning("[nur Konsole] Neues OpWatch-Passwort erzeugt (steht in config.yml): " + sb);
        }
    }
}
