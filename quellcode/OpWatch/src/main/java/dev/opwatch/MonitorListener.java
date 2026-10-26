package dev.opwatch;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class MonitorListener implements Listener {

    private final JavaPlugin plugin;
    private final EventStore store;
    private final CommandCategorizer cats;
    private final CreativeTracker tracker;
    private final boolean logConsole;
    private final boolean logCommandBlocks;
    private final boolean logJoinQuit;

    MonitorListener(JavaPlugin plugin, EventStore store, CommandCategorizer cats, CreativeTracker tracker) {
        this.plugin = plugin;
        this.store = store;
        this.cats = cats;
        this.tracker = tracker;
        this.logConsole = plugin.getConfig().getBoolean("log.console-commands", true);
        this.logCommandBlocks = plugin.getConfig().getBoolean("log.command-blocks", true);
        this.logJoinQuit = plugin.getConfig().getBoolean("log.join-quit", true);
    }

    static Map<String, Object> base(Player p, String type) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("ts", System.currentTimeMillis());
        e.put("type", type);
        e.put("player", p.getName());
        e.put("uuid", p.getUniqueId().toString());
        putLoc(e, p.getLocation());
        e.put("gm", p.getGameMode().name().toLowerCase(Locale.ROOT));
        return e;
    }

    static void putLoc(Map<String, Object> e, Location l) {
        if (l == null) return;
        if (l.getWorld() != null) e.put("world", l.getWorld().getName());
        e.put("x", l.getBlockX());
        e.put("y", l.getBlockY());
        e.put("z", l.getBlockZ());
    }

    // ------------------------------------------------------------------ commands

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent ev) {
        String msg = ev.getMessage();
        if (cats.isIgnored(msg)) return;
        Map<String, Object> e = base(ev.getPlayer(), "COMMAND");
        e.put("command", msg);
        e.put("category", cats.categorize(msg));
        if (cats.isHighlighted(msg)) e.put("notable", true);
        if (ev.isCancelled()) e.put("cancelled", true);
        store.add(e);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerCommand(ServerCommandEvent ev) {
        CommandSender s = ev.getSender();
        boolean isBlock = s instanceof BlockCommandSender;
        if (isBlock && !logCommandBlocks) return;
        if (!isBlock && !logConsole) return;
        String cmd = ev.getCommand();
        if (cats.isIgnored(cmd)) return;
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("ts", System.currentTimeMillis());
        e.put("type", "COMMAND");
        if (isBlock) {
            e.put("player", "[Commandblock]");
            putLoc(e, ((BlockCommandSender) s).getBlock().getLocation());
        } else if (s instanceof ConsoleCommandSender) {
            e.put("player", "[Konsole]");
        } else {
            e.put("player", "[" + s.getName() + "]");
        }
        e.put("command", cmd.startsWith("/") ? cmd : "/" + cmd);
        e.put("category", cats.categorize(cmd));
        if (cats.isHighlighted(cmd)) e.put("notable", true);
        e.put("source", isBlock ? "commandblock" : "console");
        store.add(e);
    }

    // ------------------------------------------------------------------ gamemode / join / quit

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent ev) {
        Player p = ev.getPlayer();
        GameMode from = p.getGameMode();
        GameMode to = ev.getNewGameMode();
        if (from == to) return;
        Map<String, Object> e = base(p, "GAMEMODE");
        e.put("from", from.name().toLowerCase(Locale.ROOT));
        e.put("to", to.name().toLowerCase(Locale.ROOT));
        e.put("detail", from.name().toLowerCase(Locale.ROOT) + " → " + to.name().toLowerCase(Locale.ROOT));
        if (to == GameMode.CREATIVE) e.put("notable", true);
        store.add(e);

        if (from == GameMode.CREATIVE) tracker.leaveCreative(p, "zu " + to.name().toLowerCase(Locale.ROOT));
        if (to == GameMode.CREATIVE) {
            // inventory is unchanged by the switch, snapshot right away
            tracker.enterCreative(p);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent ev) {
        Player p = ev.getPlayer();
        if (logJoinQuit) {
            Map<String, Object> e = base(p, "JOIN");
            e.put("detail", "Server betreten" + (p.isOp() ? " (OP)" : ""));
            store.add(e);
        }
        if (p.getGameMode() == GameMode.CREATIVE) tracker.enterCreative(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent ev) {
        Player p = ev.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE) tracker.leaveCreative(p, "Server verlassen");
        tracker.forget(p.getUniqueId());
        if (logJoinQuit) {
            Map<String, Object> e = base(p, "QUIT");
            e.put("detail", "Server verlassen");
            store.add(e);
        }
    }

    // ------------------------------------------------------------------ creative items

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreative(InventoryCreativeEvent ev) {
        HumanEntity h = ev.getWhoClicked();
        if (h instanceof Player p) tracker.creativeAction(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent ev) {
        Player p = ev.getPlayer();
        if (p.getGameMode() != GameMode.CREATIVE) return;
        tracker.dropped(p, ev.getItemDrop().getItemStack());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent ev) {
        LivingEntity le = ev.getEntity();
        if (le instanceof Player p && p.getGameMode() == GameMode.CREATIVE) tracker.silentResync(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent ev) {
        if (!(ev.getPlayer() instanceof Player p) || p.getGameMode() != GameMode.CREATIVE) return;
        Inventory top = ev.getInventory();
        InventoryType t = top.getType();
        if (t == InventoryType.PLAYER || t == InventoryType.CRAFTING || t == InventoryType.CREATIVE) return;
        tracker.containerOpened(p, top, t.name().toLowerCase(Locale.ROOT), top.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent ev) {
        if (ev.getPlayer() instanceof Player p) tracker.containerClosed(p);
    }
}
