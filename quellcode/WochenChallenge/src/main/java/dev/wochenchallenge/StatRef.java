package dev.wochenchallenge;

import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;

import java.util.Locale;

/** One vanilla statistic, optionally for a specific block/item or entity, e.g. "MINE_BLOCK:DIAMOND_ORE". */
record StatRef(String key, Statistic stat, Material mat, EntityType ent) {

    /** Returns null when the statistic/material/entity does not exist in this Minecraft version. */
    static StatRef parse(String spec) {
        try {
            String[] p = spec.trim().toUpperCase(Locale.ROOT).split(":");
            Statistic st = Statistic.valueOf(p[0]);
            Statistic.Type type = st.getType();
            if (type == Statistic.Type.UNTYPED) return p.length == 1 ? new StatRef(p[0], st, null, null) : null;
            if (p.length < 2) return null;
            if (type == Statistic.Type.ENTITY) return new StatRef(p[0] + ":" + p[1], st, null, EntityType.valueOf(p[1]));
            Material m = Material.matchMaterial(p[1]);
            return m == null ? null : new StatRef(p[0] + ":" + p[1], st, m, null);
        } catch (Exception e) {
            return null;
        }
    }

    int read(OfflinePlayer pl) {
        try {
            if (mat != null) return pl.getStatistic(stat, mat);
            if (ent != null) return pl.getStatistic(stat, ent);
            return pl.getStatistic(stat);
        } catch (Exception e) {
            return 0;
        }
    }
}
