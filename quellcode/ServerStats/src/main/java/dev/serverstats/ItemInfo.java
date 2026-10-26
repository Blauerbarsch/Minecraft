package dev.serverstats;

import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a player's live state into plain maps for the web page.
 * Main thread only. Every lookup that changed between Minecraft versions is wrapped,
 * so one unusual item can never break the whole page.
 */
final class ItemInfo {
    private ItemInfo() {}

    static Map<String, Object> item(ItemStack it) {
        if (it == null || it.getType().isAir() || it.getAmount() <= 0) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", it.getType().name().toLowerCase(Locale.ROOT));
        if (it.getAmount() > 1) m.put("amount", it.getAmount());

        List<Map<String, Object>> ench = new ArrayList<>();
        try {
            for (Map.Entry<Enchantment, Integer> e : it.getEnchantments().entrySet()) ench.add(ench(e.getKey(), e.getValue()));
        } catch (Throwable ignored) { }

        ItemMeta meta = it.hasItemMeta() ? it.getItemMeta() : null;
        if (meta != null) {
            try {
                if (meta.hasDisplayName()) m.put("name", meta.getDisplayName().replaceAll("§.", ""));
            } catch (Throwable ignored) { }
            try {
                if (meta instanceof EnchantmentStorageMeta book)
                    for (Map.Entry<Enchantment, Integer> e : book.getStoredEnchants().entrySet()) ench.add(ench(e.getKey(), e.getValue()));
            } catch (Throwable ignored) { }
            try {
                if (meta.isUnbreakable()) m.put("unbreakable", true);
            } catch (Throwable ignored) { }
            try {
                if (meta instanceof Damageable d) {
                    int max = it.getType().getMaxDurability();
                    try { if (d.hasMaxDamage()) max = d.getMaxDamage(); } catch (Throwable ignored) { }
                    if (max > 0) {
                        m.put("damage", d.getDamage());
                        m.put("maxDamage", max);
                    }
                }
            } catch (Throwable ignored) { }
            try {
                if (meta instanceof ArmorMeta am && am.hasTrim()) {
                    ArmorTrim t = am.getTrim();
                    Map<String, Object> tr = new LinkedHashMap<>();
                    tr.put("material", t.getMaterial().getKey().getKey());
                    tr.put("pattern", t.getPattern().getKey().getKey());
                    m.put("trim", tr);
                }
            } catch (Throwable ignored) { }
        } else {
            int max = it.getType().getMaxDurability();
            if (max > 0) { m.put("damage", 0); m.put("maxDamage", max); }
        }
        if (!ench.isEmpty()) m.put("enchants", ench);
        return m;
    }

    private static Map<String, Object> ench(Enchantment e, int level) {
        Map<String, Object> m = new LinkedHashMap<>();
        String id;
        try { id = e.getKey().getKey(); } catch (Throwable t) { id = String.valueOf(e); }
        m.put("id", id);
        m.put("level", level);
        return m;
    }

    static List<Object> items(ItemStack[] arr) {
        List<Object> l = new ArrayList<>();
        if (arr != null) for (ItemStack it : arr) l.add(item(it));
        return l;
    }

    private static double attr(Player p, Attribute a, double def) {
        try {
            AttributeInstance i = p.getAttribute(a);
            return i == null ? def : i.getValue();
        } catch (Throwable t) {
            return def;
        }
    }

    private static double round1(double v) { return Math.round(v * 10) / 10.0; }

    /** Everything the player page shows about one online player. */
    static Map<String, Object> snapshot(Player p) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("uuid", p.getUniqueId().toString());
        s.put("name", p.getName());
        s.put("gm", p.getGameMode().name().toLowerCase(Locale.ROOT));
        s.put("ping", p.getPing());

        Map<String, Object> v = new LinkedHashMap<>();
        v.put("health", round1(p.getHealth()));
        v.put("maxHealth", round1(attr(p, Attribute.MAX_HEALTH, 20)));
        v.put("absorption", round1(p.getAbsorptionAmount()));
        v.put("food", p.getFoodLevel());
        v.put("saturation", round1(p.getSaturation()));
        v.put("level", p.getLevel());
        v.put("exp", Math.round(p.getExp() * 100) / 100.0);
        v.put("armor", round1(attr(p, Attribute.ARMOR, 0)));
        v.put("toughness", round1(attr(p, Attribute.ARMOR_TOUGHNESS, 0)));
        s.put("vitals", v);

        PlayerInventory inv = p.getInventory();
        Map<String, Object> eq = new LinkedHashMap<>();
        eq.put("head", item(inv.getHelmet()));
        eq.put("chest", item(inv.getChestplate()));
        eq.put("legs", item(inv.getLeggings()));
        eq.put("feet", item(inv.getBoots()));
        eq.put("mainhand", item(inv.getItemInMainHand()));
        eq.put("offhand", item(inv.getItemInOffHand()));
        s.put("equipment", eq);

        List<Map<String, Object>> fx = new ArrayList<>();
        try {
            for (PotionEffect e : p.getActivePotionEffects()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", e.getType().getKey().getKey());
                m.put("level", e.getAmplifier() + 1);
                m.put("seconds", e.isInfinite() ? -1 : e.getDuration() / 20);
                fx.add(m);
            }
        } catch (Throwable ignored) { }
        s.put("effects", fx);

        // only for admins (filtered later)
        Location l = p.getLocation();
        Map<String, Object> loc = new LinkedHashMap<>();
        loc.put("world", l.getWorld() == null ? "?" : l.getWorld().getName());
        loc.put("x", l.getBlockX());
        loc.put("y", l.getBlockY());
        loc.put("z", l.getBlockZ());
        s.put("loc", loc);
        s.put("inventory", items(inv.getStorageContents()));
        s.put("enderchest", items(p.getEnderChest().getContents()));
        return s;
    }
}
