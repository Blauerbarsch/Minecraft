package dev.graverestore;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /grab liste [Spieler]          – letzte Tode mit Grabkiste
 * /grab zurück <Spieler> [Nr]    – Inventar dieses Todes 1:1 zurückgeben (Nr 1 = letzter Tod)
 * Nur über die Server-Konsole bzw. die Web-Konsole – sonst könnte sich jeder OP Items verdoppeln.
 */
public final class GraveRestorePlugin extends JavaPlugin implements CommandExecutor {

    private static final String[] EMBEDDED = {
            "audit-2026-10-21.csv", "audit-2026-10-22.csv", "audit-2026-10-23.csv", "audit-2026-10-24.csv", "audit-2026-10-25.csv"};
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("EE dd.MM. HH:mm", Locale.GERMAN);

    private File restoredFile;
    private ZoneId zone = ZoneId.of("Europe/Berlin");
    private final Map<String, Object> restored = new LinkedHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try { zone = ZoneId.of(getConfig().getString("timezone", "Europe/Berlin")); } catch (Exception ignored) { }
        File own = new File(getDataFolder(), "audit");
        own.mkdirs();
        // the logs of the last days ship with the jar, so the first restore works even if the grave plugin keeps them elsewhere
        for (String name : EMBEDDED) {
            File target = new File(own, name);
            if (target.exists()) continue;
            try (InputStream in = getResource("embedded/" + name)) {
                if (in != null) Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                getLogger().warning("Konnte " + name + " nicht auspacken: " + e.getMessage());
            }
        }
        restoredFile = new File(getDataFolder(), "restored.json");
        loadRestored();
        if (getCommand("grab") != null) getCommand("grab").setExecutor(this);
        List<File> files = files();
        getLogger().info(files.size() + " Grab-Logdateien gefunden. Befehle: grab liste [Spieler] / grab zurück <Spieler> [Nr]");
    }

    private List<File> files() {
        List<File> roots = new ArrayList<>();
        roots.add(getDataFolder().getParentFile()); // plugins/ – the grave plugin keeps its audit files in there
        roots.add(new File("logs"));
        return GraveLog.findFiles(roots, getLogger());
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage("§cNur über die Server-Konsole oder die Web-Konsole – sonst könnte man Items verdoppeln.");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        List<GraveLog.Grave> all = GraveLog.read(files(), getLogger());
        switch (sub) {
            case "liste", "list" -> list(sender, all, args.length > 1 ? args[1] : null);
            case "zurück", "zurueck", "restore" -> {
                if (args.length < 2) { usage(sender); return true; }
                int nr = 1;
                if (args.length > 2) {
                    try { nr = Integer.parseInt(args[2]); } catch (NumberFormatException e) { usage(sender); return true; }
                }
                boolean force = false, special = false;
                for (int i = 3; i < args.length; i++) {
                    if (args[i].equalsIgnoreCase("nochmal")) force = true;
                    if (args[i].equalsIgnoreCase("spezial")) special = true;
                }
                restore(sender, all, args[1], nr, force, special);
            }
            default -> usage(sender);
        }
        return true;
    }

    private void usage(CommandSender s) {
        s.sendMessage("grab liste [Spieler]            – letzte Tode anzeigen");
        s.sendMessage("grab zurück <Spieler> [Nr]      – Inventar 1:1 zurückgeben (Nr aus der Liste, 1 = letzter Tod)");
        s.sendMessage("grab zurück <Spieler> <Nr> spezial – nur Items mit Zusatzdaten (verzaubert, beschädigt, Shulker-Inhalt …)");
    }

    private static List<GraveLog.Grave> of(List<GraveLog.Grave> all, String player) {
        List<GraveLog.Grave> l = new ArrayList<>();
        for (GraveLog.Grave g : all) if (player == null || g.ownerName.equalsIgnoreCase(player)) l.add(g);
        return l;
    }

    private String when(GraveLog.Grave g) {
        return Instant.ofEpochMilli(g.created).atZone(zone).format(FMT);
    }

    private void list(CommandSender s, List<GraveLog.Grave> all, String player) {
        List<GraveLog.Grave> l = of(all, player);
        if (l.isEmpty()) {
            s.sendMessage(player == null ? "Keine Grabkisten in den Logs gefunden." : "Keine Tode von " + player + " in den Logs gefunden.");
            return;
        }
        s.sendMessage("Letzte Tode" + (player == null ? "" : " von " + player) + " (Nr für „grab zurück“):");
        int shown = 0;
        // numbering is per player so that "Nr" matches "grab zurück <Spieler> <Nr>"
        Map<String, Integer> counter = new LinkedHashMap<>();
        for (GraveLog.Grave g : all) {
            int n = counter.merge(g.ownerName.toLowerCase(Locale.ROOT), 1, Integer::sum);
            if (player != null && !g.ownerName.equalsIgnoreCase(player)) continue;
            if (shown++ >= 15) break;
            String done = restored.containsKey(g.key) ? "  [schon zurückgegeben]" : "";
            s.sendMessage(String.format("  %s #%d  %s  %s %d %d %d  %d Items  –  %s%s",
                    g.ownerName, n, when(g), g.world, g.x, g.y, g.z, g.itemCount(), g.status(), done));
        }
    }

    private void restore(CommandSender s, List<GraveLog.Grave> all, String player, int nr, boolean force, boolean special) {
        List<GraveLog.Grave> l = of(all, player);
        if (nr < 1 || nr > l.size()) {
            s.sendMessage("Keinen Tod Nr. " + nr + " von " + player + " gefunden. Siehe: grab liste " + player);
            return;
        }
        GraveLog.Grave g = l.get(nr - 1);
        Player target = getServer().getPlayerExact(g.ownerName);
        if (target == null) {
            s.sendMessage(g.ownerName + " ist nicht online – die Items können nur direkt ins Inventar gegeben werden.");
            return;
        }
        String recKey = special ? g.key + "#spezial" : g.key;
        if (!force) {
            if (restored.containsKey(recKey)) {
                s.sendMessage("Dieser Tod (" + when(g) + ") wurde schon zurückgegeben. Nochmal: grab zurück " + player + " " + nr + " nochmal");
                return;
            }
            if (!g.lost()) {
                s.sendMessage("Achtung: Diese Kiste wurde nicht gelöscht (" + g.status() + "). Die Items sind vermutlich nicht weg.");
                s.sendMessage("Trotzdem zurückgeben: grab zurück " + player + " " + nr + " nochmal");
                return;
            }
        }
        int free = 0;
        for (ItemStack it : target.getInventory().getStorageContents()) if (it == null || it.getType().isAir()) free++;

        List<Map<String, Object>> give = new ArrayList<>();
        for (Map<String, Object> it : g.restoreItems())
            if (!special || (it.get("components") instanceof Map<?, ?> c && !c.isEmpty())) give.add(it);
        if (give.isEmpty()) {
            s.sendMessage("In dieser Kiste lag beim Entfernen nichts mehr – es gibt nichts zurückzugeben.");
            return;
        }
        int ok = 0, failed = 0;
        List<String> failedIds = new ArrayList<>();
        for (Map<String, Object> item : give) {
            ItemStack stack = build(item);
            if (stack == null) {
                failed++;
                failedIds.add(String.valueOf(item.get("id")).replace("minecraft:", ""));
                continue;
            }
            for (ItemStack left : target.getInventory().addItem(stack).values())
                target.getWorld().dropItemNaturally(target.getLocation(), left);
            ok++;
        }
        if (ok == 0) {
            s.sendMessage("Es konnte kein Item erzeugt werden – nichts wurde gegeben. Details stehen oben in der Konsole.");
            return;
        }
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("player", g.ownerName);
        rec.put("death", g.ts);
        rec.put("restoredAt", System.currentTimeMillis());
        rec.put("stacks", ok);
        if (ok > 0) restored.put(recKey, rec);
        saveRestored();

        s.sendMessage("Inventar vom " + when(g) + " an " + target.getName() + " zurückgegeben: " + ok + " von " + give.size() + " Stapeln"
                + (failed > 0 ? " – NICHT geklappt: " + String.join(", ", failedIds) + " (siehe Konsole)" : "") + ".");
        if (give.size() > free) s.sendMessage("Hinweis: Das Inventar hatte nur " + free + " freie Plätze – der Rest liegt vor den Füßen.");
        target.sendMessage("§aDein Inventar vom " + when(g) + " wurde wiederhergestellt (" + ok + " Stapel). Rüstung bitte selbst wieder anziehen.");
    }

    /**
     * Rebuilds one item. Only ways that report failure are used, so "zurückgegeben" is always true:
     * 1) Minecraft's own item parser (the one behind /give) via ItemFactory#createItemStack
     * 2) Paper's JSON item format
     */
    private ItemStack build(Map<String, Object> item) {
        int count = ((Number) item.getOrDefault("count", 1)).intValue();
        String text = GraveLog.itemString(item);
        try {
            ItemStack st = getServer().getItemFactory().createItemStack(text);
            if (st != null && !st.getType().isAir()) {
                st.setAmount(count);
                return st;
            }
        } catch (Throwable t) {
            getLogger().warning("Item " + item.get("id") + " (Minecraft-Parser): " + t.getMessage());
        }
        ItemStack st = ItemJson.toItem(getServer(), item, getLogger());
        if (st != null) return st;
        getLogger().warning("Item " + item.get("id") + " konnte nicht erzeugt werden. Von Hand: " + GraveLog.giveCommand("<Spieler>", item));
        return null;
    }

    // ------------------------------------------------------------------ remember what was given back (no double restores)

    @SuppressWarnings("unchecked")
    private void loadRestored() {
        if (!restoredFile.isFile()) return;
        try {
            restored.putAll((Map<String, Object>) Json.parse(Files.readString(restoredFile.toPath(), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            getLogger().warning("restored.json nicht lesbar: " + e.getMessage());
        }
    }

    private void saveRestored() {
        try {
            Files.writeString(restoredFile.toPath(), Json.write(restored), StandardCharsets.UTF_8);
        } catch (Exception e) {
            getLogger().warning("restored.json nicht speicherbar: " + e.getMessage());
        }
    }
}
