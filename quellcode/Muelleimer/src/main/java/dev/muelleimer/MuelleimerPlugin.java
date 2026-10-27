package dev.muelleimer;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /trash            Mülleimer öffnen – was beim Schließen drin liegt, ist weg
 * /trash undo       das zuletzt Weggeworfene zurückholen (kurze Zeit)
 * /trash block [S]  Mülleimer-Block geben (OP / Konsole)
 * Block: ein Kessel mit Markierung. Rechtsklick öffnet den Mülleimer.
 */
public final class MuelleimerPlugin extends JavaPlugin implements Listener, CommandExecutor {

    /** Marks our inventories, so other inventories are never touched. */
    static final class TrashHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private NamespacedKey itemKey, recipeKey;
    private TrashMarks marks;
    private UndoBuffer<ItemStack> undo;
    private String title;
    private int rows;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Msg.init("Mülleimer");
        itemKey = new NamespacedKey(this, "muelleimer");
        recipeKey = new NamespacedKey(this, "muelleimer_rezept");
        marks = new TrashMarks(new NamespacedKey(this, "muelleimer_bloecke"));
        undo = new UndoBuffer<>(Math.max(0, getConfig().getInt("undo-seconds", 120)) * 1000L);
        title = getConfig().getString("title", "Mülleimer – wird beim Schließen geleert");
        rows = Math.max(1, Math.min(6, getConfig().getInt("rows", 4)));

        if (getConfig().getBoolean("recipe", true)) {
            try {
                ShapelessRecipe r = new ShapelessRecipe(recipeKey, trashItem(1));
                r.addIngredient(Material.CAULDRON);
                r.addIngredient(Material.CACTUS);
                getServer().addRecipe(r);
            } catch (Exception e) {
                getLogger().warning("Rezept konnte nicht angelegt werden: " + e.getMessage());
            }
        }
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("trash") != null) getCommand("trash").setExecutor(this);
        getServer().getScheduler().runTaskTimer(this, () -> undo.purge(System.currentTimeMillis()), 1200L, 1200L);
    }

    @Override
    public void onDisable() {
        try { getServer().removeRecipe(recipeKey); } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ the item

    ItemStack trashItem(int amount) {
        ItemStack it = new ItemStack(Material.CAULDRON, Math.max(1, amount));
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName("§6Mülleimer");
            m.setLore(List.of("§7Rechtsklick: Müll reinwerfen", "§7Alles darin wird beim Schließen gelöscht"));
            m.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
            try { m.setEnchantmentGlintOverride(true); } catch (Throwable ignored) { } // shimmer, so it stands out
            it.setItemMeta(m);
        }
        return it;
    }

    private boolean isTrashItem(ItemStack it) {
        if (it == null || it.getType() != Material.CAULDRON || !it.hasItemMeta()) return false;
        ItemMeta m = it.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ open / close

    void open(Player p) {
        try {
            TrashHolder h = new TrashHolder();
            Inventory inv = makeInventory(h);
            h.inv = inv;
            p.openInventory(inv);
            sound(p, "minecraft:block.barrel.open", 0.6f, 1.2f);
        } catch (Throwable t) {
            getLogger().log(java.util.logging.Level.SEVERE, "Mülleimer konnte nicht geöffnet werden", t);
            Msg.err(p, "Der Mülleimer konnte nicht geöffnet werden – Details stehen in der Server-Konsole.");
        }
    }

    /** Paper prefers a text component as title; the plain-text version is only the fallback. */
    private Inventory makeInventory(TrashHolder h) {
        int size = rows * 9;
        try {
            Class<?> component = Class.forName("net.kyori.adventure.text.Component");
            Object text = component.getMethod("text", String.class).invoke(null, title);
            java.lang.reflect.Method m = getServer().getClass().getMethod("createInventory", InventoryHolder.class, int.class, component);
            return (Inventory) m.invoke(getServer(), h, size, text);
        } catch (Throwable t) {
            getLogger().fine("Titel als Text-Komponente ging nicht (" + t + "), nehme einfachen Titel.");
        }
        return getServer().createInventory(h, size, title);
    }

    private void sound(Player p, String key, float volume, float pitch) {
        try { p.playSound(p.getLocation(), key, volume, pitch); } catch (Throwable ignored) { } // sound is optional
    }

    @EventHandler
    public void onClose(InventoryCloseEvent ev) {
        if (!(ev.getInventory().getHolder() instanceof TrashHolder)) return;
        if (!(ev.getPlayer() instanceof Player p)) return;
        List<ItemStack> thrown = new ArrayList<>();
        int count = 0;
        for (ItemStack it : ev.getInventory().getContents()) {
            if (it == null || it.getType().isAir()) continue;
            thrown.add(it.clone());
            count += it.getAmount();
        }
        ev.getInventory().clear();
        if (thrown.isEmpty()) return;
        long now = System.currentTimeMillis();
        undo.put(p.getUniqueId(), thrown, now);
        sound(p, "minecraft:block.composter.empty", 0.8f, 0.9f);
        long sec = undo.secondsLeft(p.getUniqueId(), now);
        Msg.info(p, Msg.val(count) + " Items weggeworfen." + (sec > 0 ? " Versehen? " + Msg.cmd("/trash undo") + " (noch " + sec + " Sek.)" : ""));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent ev) {
        try { ev.getPlayer().discoverRecipe(recipeKey); } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ the block

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent ev) {
        if (!isTrashItem(ev.getItemInHand())) return;
        marks.mark(ev.getBlockPlaced());
        Msg.info(ev.getPlayer(), "Mülleimer aufgestellt – Rechtsklick zum Benutzen.");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent ev) {
        Block b = ev.getBlock();
        if (b.getType() != Material.CAULDRON || !marks.is(b)) return;
        marks.unmark(b);
        ev.setDropItems(false);
        if (ev.getPlayer().getGameMode() != org.bukkit.GameMode.CREATIVE)
            b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.3, 0.5), trashItem(1));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent ev) {
        if (ev.getAction() != Action.RIGHT_CLICK_BLOCK || ev.getClickedBlock() == null) return;
        Block b = ev.getClickedBlock();
        if (b.getType() != Material.CAULDRON || !marks.is(b)) return;
        Player p = ev.getPlayer();
        // sneaking with a block in hand: place blocks next to it as usual
        if (p.isSneaking() && ev.getItem() != null && ev.getItem().getType().isBlock()) return;
        ev.setCancelled(true); // no water/lava buckets etc.
        if (ev.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return; // open once, not for both hands
        if (!p.hasPermission("muelleimer.use")) return;
        open(p);
    }

    /** Rain, dripstone or buckets would turn it into a water cauldron and lose the mark. */
    @EventHandler(ignoreCancelled = true)
    public void onLevel(CauldronLevelChangeEvent ev) {
        if (marks.is(ev.getBlock())) ev.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonPush(BlockPistonExtendEvent ev) {
        for (Block b : ev.getBlocks()) if (b.getType() == Material.CAULDRON && marks.is(b)) { ev.setCancelled(true); return; }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonPull(BlockPistonRetractEvent ev) {
        for (Block b : ev.getBlocks()) if (b.getType() == Material.CAULDRON && marks.is(b)) { ev.setCancelled(true); return; }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent ev) {
        ev.blockList().removeIf(b -> b.getType() == Material.CAULDRON && marks.is(b));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent ev) {
        ev.blockList().removeIf(b -> b.getType() == Material.CAULDRON && marks.is(b));
    }

    // ------------------------------------------------------------------ /trash

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "", "open" -> {
                if (!(sender instanceof Player p)) { Msg.err(sender, "Das geht nur im Spiel."); return true; }
                if (!p.hasPermission("muelleimer.use")) { Msg.err(p, "Dazu fehlt dir die Berechtigung."); return true; }
                open(p);
            }
            case "undo", "zurück", "zurueck" -> {
                if (!(sender instanceof Player p)) { Msg.err(sender, "Das geht nur im Spiel."); return true; }
                List<ItemStack> back = undo.take(p.getUniqueId(), System.currentTimeMillis());
                if (back.isEmpty()) { Msg.info(p, "Nichts zum Zurückholen – entweder nichts weggeworfen oder schon zu lange her."); return true; }
                int n = 0;
                for (ItemStack it : back) {
                    n += it.getAmount();
                    for (ItemStack left : p.getInventory().addItem(it).values())
                        p.getWorld().dropItemNaturally(p.getLocation(), left);
                }
                Msg.ok(p, n + " Items zurückgeholt.");
            }
            case "block", "geben" -> {
                if (!sender.hasPermission("muelleimer.block")) { Msg.err(sender, "Das dürfen nur OPs."); return true; }
                Player target = args.length > 1 ? getServer().getPlayerExact(args[1]) : (sender instanceof Player p ? p : null);
                if (target == null) { Msg.err(sender, "Spieler nicht gefunden. Nutzung: " + Msg.cmd("/trash block <Spieler> [Anzahl]")); return true; }
                int amount = 1;
                if (args.length > 2) try { amount = Math.max(1, Math.min(64, Integer.parseInt(args[2]))); } catch (NumberFormatException ignored) { }
                for (ItemStack left : target.getInventory().addItem(trashItem(amount)).values())
                    target.getWorld().dropItemNaturally(target.getLocation(), left);
                Msg.ok(sender, amount + "× Mülleimer an " + Msg.name(target.getName()) + Msg.OK + " gegeben.");
            }
            default -> {
                Msg.head(sender, "Mülleimer");
                Msg.line(sender, Msg.cmd("/trash") + " – öffnen");
                Msg.line(sender, Msg.cmd("/trash undo") + " – zuletzt Weggeworfenes zurückholen");
                if (sender.hasPermission("muelleimer.block")) Msg.line(sender, Msg.cmd("/trash block <Spieler> [Anzahl]") + " – Mülleimer-Block geben");
            }
        }
        return true;
    }
}
