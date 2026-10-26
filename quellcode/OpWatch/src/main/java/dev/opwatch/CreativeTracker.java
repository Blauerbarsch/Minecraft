package dev.opwatch;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Works out which items a player really got out of creative mode.
 *
 * The creative inventory has no "take item" event, so we compare the player's item totals before
 * and after every creative inventory action. Moving an item inside the inventory changes nothing in
 * total; pulling one from the creative menu (or middle-click pick block) raises the total. Gains are
 * collected for a few seconds and written as one log entry.
 *
 * On top of that, the full inventory + ender chest is snapshotted when someone enters creative and
 * compared when they leave it, which shows what they took with them into survival.
 */
final class CreativeTracker {

    private final Plugin plugin;
    private final EventStore store;
    private final int flushTicks;
    private final Set<String> notableExact = new HashSet<>();
    private final List<String> notableSuffix = new ArrayList<>();

    private final Map<UUID, Map<String, Integer>> ledger = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> entry = new HashMap<>();
    private final Map<UUID, Long> entryTime = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> pending = new HashMap<>();
    private final Map<UUID, BukkitTask> flushTasks = new HashMap<>();
    private final Set<UUID> refreshScheduled = new HashSet<>();
    private final Map<UUID, ContainerView> openContainers = new HashMap<>();

    private record ContainerView(Inventory inv, Map<String, Integer> before, String name, Location loc) {}

    CreativeTracker(Plugin plugin, EventStore store, FileConfiguration cfg) {
        this.plugin = plugin;
        this.store = store;
        this.flushTicks = Math.max(20, cfg.getInt("creative.group-seconds", 4) * 20);
        for (String s : cfg.getStringList("creative.notable-items")) {
            String k = s.toLowerCase(Locale.ROOT).trim();
            if (k.startsWith("*")) notableSuffix.add(k.substring(1));
            else notableExact.add(k);
        }
    }

    // ------------------------------------------------------------------ helpers

    static String key(ItemStack it) {
        return it.getType().name().toLowerCase(Locale.ROOT);
    }

    static Map<String, Integer> count(ItemStack[] contents) {
        Map<String, Integer> m = new HashMap<>();
        if (contents == null) return m;
        for (ItemStack it : contents) {
            if (it == null || it.getType().isAir() || it.getAmount() <= 0) continue;
            m.merge(key(it), it.getAmount(), Integer::sum);
        }
        return m;
    }

    static Map<String, Integer> fullCount(Player p) {
        Map<String, Integer> m = count(p.getInventory().getContents());
        count(p.getEnderChest().getContents()).forEach((k, v) -> m.merge(k, v, Integer::sum));
        return m;
    }

    /** a - b, only entries that are positive. */
    static Map<String, Integer> gained(Map<String, Integer> now, Map<String, Integer> before) {
        Map<String, Integer> out = new TreeMap<>();
        for (var e : now.entrySet()) {
            int diff = e.getValue() - before.getOrDefault(e.getKey(), 0);
            if (diff > 0) out.put(e.getKey(), diff);
        }
        return out;
    }

    boolean isNotable(String item) {
        if (notableExact.contains(item)) return true;
        for (String s : notableSuffix) if (item.endsWith(s)) return true;
        return false;
    }

    private List<Map<String, Object>> itemList(Map<String, Integer> items) {
        List<Map<String, Object>> l = new ArrayList<>();
        for (var e : items.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("item", e.getKey());
            m.put("amount", e.getValue());
            if (isNotable(e.getKey())) m.put("notable", true);
            l.add(m);
        }
        return l;
    }

    private boolean anyNotable(Map<String, Integer> items) {
        for (String k : items.keySet()) if (isNotable(k)) return true;
        return false;
    }

    private static int total(Map<String, Integer> m) {
        int t = 0;
        for (int v : m.values()) t += v;
        return t;
    }

    // ------------------------------------------------------------------ lifecycle

    void initOnlinePlayers() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.CREATIVE) enterCreative(p);
        }
    }

    void enterCreative(Player p) {
        UUID id = p.getUniqueId();
        entry.put(id, fullCount(p));
        entryTime.put(id, System.currentTimeMillis());
        ledger.put(id, count(p.getInventory().getContents()));
    }

    /** Called when a player leaves creative (gamemode change or quit). */
    void leaveCreative(Player p, String reason) {
        UUID id = p.getUniqueId();
        refreshNow(p);
        flush(id, p);
        Map<String, Integer> before = entry.remove(id);
        Long since = entryTime.remove(id);
        ledger.remove(id);
        if (before == null) return;

        Map<String, Integer> kept = gained(fullCount(p), before);
        Map<String, Object> e = MonitorListener.base(p, "CREATIVE_EXIT");
        e.put("reason", reason);
        if (since != null) e.put("durationSec", (System.currentTimeMillis() - since) / 1000);
        e.put("items", itemList(kept));
        e.put("total", total(kept));
        if (anyNotable(kept)) e.put("notable", true);
        e.put("detail", kept.isEmpty()
                ? "Creative verlassen (" + reason + ") – keine neuen Items im Inventar/Enderchest"
                : "Creative verlassen (" + reason + ") – " + total(kept) + " neue Items mitgenommen");
        store.add(e);
    }

    void forget(UUID id) {
        BukkitTask t = flushTasks.remove(id);
        if (t != null) t.cancel();
        pending.remove(id);
        refreshScheduled.remove(id);
        openContainers.remove(id);
    }

    void flushAll() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.CREATIVE) {
                refreshNow(p);
                flush(p.getUniqueId(), p);
            }
        }
    }

    // ------------------------------------------------------------------ creative inventory

    /** Some creative inventory action happened; compare totals on the next tick. */
    void creativeAction(Player p) {
        UUID id = p.getUniqueId();
        if (!entry.containsKey(id)) enterCreative(p);
        if (!refreshScheduled.add(id)) return;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            refreshScheduled.remove(id);
            if (p.isOnline()) refreshNow(p);
        }, 1L);
    }

    private void refreshNow(Player p) {
        UUID id = p.getUniqueId();
        Map<String, Integer> before = ledger.get(id);
        Map<String, Integer> now = count(p.getInventory().getContents());
        ledger.put(id, now);
        if (before == null) return;
        Map<String, Integer> gain = gained(now, before);
        if (gain.isEmpty()) return;
        Map<String, Integer> pend = pending.computeIfAbsent(id, k -> new TreeMap<>());
        gain.forEach((k, v) -> pend.merge(k, v, Integer::sum));
        BukkitTask old = flushTasks.remove(id);
        if (old != null) old.cancel();
        flushTasks.put(id, plugin.getServer().getScheduler().runTaskLater(plugin, () -> flush(id, p), flushTicks));
    }

    /** Inventory changed for a non-creative reason (pickup, chest); update baseline without logging. */
    void silentResync(Player p) {
        UUID id = p.getUniqueId();
        if (!ledger.containsKey(id)) return;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && ledger.containsKey(id) && !refreshScheduled.contains(id)) {
                ledger.put(id, count(p.getInventory().getContents()));
            }
        }, 2L);
    }

    private void flush(UUID id, Player p) {
        BukkitTask t = flushTasks.remove(id);
        if (t != null) t.cancel();
        Map<String, Integer> items = pending.remove(id);
        if (items == null || items.isEmpty()) return;
        Map<String, Object> e = MonitorListener.base(p, "CREATIVE_ITEMS");
        e.put("items", itemList(items));
        e.put("total", total(items));
        if (anyNotable(items)) e.put("notable", true);
        e.put("detail", "Aus Creative-Inventar genommen: " + total(items) + " Items");
        store.add(e);
    }

    // ------------------------------------------------------------------ containers in creative

    void containerOpened(Player p, Inventory top, String name, Location loc) {
        openContainers.put(p.getUniqueId(), new ContainerView(top, count(top.getContents()), name, loc));
    }

    void containerClosed(Player p) {
        ContainerView v = openContainers.remove(p.getUniqueId());
        if (v == null) return;
        Map<String, Integer> after = count(v.inv().getContents());
        Map<String, Integer> put = gained(after, v.before());
        Map<String, Integer> took = gained(v.before(), after);
        if (!put.isEmpty() || !took.isEmpty()) {
            Map<String, Object> e = MonitorListener.base(p, "CREATIVE_CONTAINER");
            e.put("container", v.name());
            if (v.loc() != null) {
                e.put("cx", v.loc().getBlockX());
                e.put("cy", v.loc().getBlockY());
                e.put("cz", v.loc().getBlockZ());
            }
            e.put("items", itemList(put));
            e.put("taken", itemList(took));
            e.put("total", total(put));
            if (anyNotable(put)) e.put("notable", true);
            StringBuilder d = new StringBuilder("Creative-Spieler hat ").append(v.name());
            if (v.loc() != null) d.append(" bei ").append(v.loc().getBlockX()).append(' ')
                    .append(v.loc().getBlockY()).append(' ').append(v.loc().getBlockZ());
            d.append(" benutzt: ").append(total(put)).append(" reingelegt, ").append(total(took)).append(" rausgenommen");
            e.put("detail", d.toString());
            store.add(e);
        }
        silentResync(p);
    }

    void dropped(Player p, ItemStack it) {
        Map<String, Integer> m = new TreeMap<>();
        m.put(key(it), it.getAmount());
        Map<String, Object> e = MonitorListener.base(p, "CREATIVE_DROP");
        e.put("items", itemList(m));
        e.put("total", it.getAmount());
        if (anyNotable(m)) e.put("notable", true);
        e.put("detail", "Im Creative gedroppt: " + it.getAmount() + "x " + key(it));
        store.add(e);
        silentResync(p);
    }
}
