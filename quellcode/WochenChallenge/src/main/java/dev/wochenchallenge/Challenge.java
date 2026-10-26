package dev.wochenchallenge;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/** One challenge from config.yml. "most" = highest value at week end wins, "race" = first to reach the target. */
record Challenge(String id, String title, String description, boolean race, String unit,
                 double divisor, int decimals, double target, List<StatRef> refs) {

    static Challenge from(String id, ConfigurationSection s, Logger log) {
        List<StatRef> refs = new ArrayList<>();
        for (String spec : s.getStringList("stats")) {
            StatRef r = StatRef.parse(spec);
            if (r != null) refs.add(r);
            else log.warning("Challenge '" + id + "': Statistik '" + spec + "' gibt es nicht – wird ignoriert.");
        }
        if (refs.isEmpty()) {
            log.warning("Challenge '" + id + "' hat keine gültige Statistik und wird übersprungen.");
            return null;
        }
        boolean race = "race".equalsIgnoreCase(s.getString("mode", "most"));
        double div = s.getDouble("divisor", 1);
        return new Challenge(id,
                s.getString("title", id),
                s.getString("description", ""),
                race,
                s.getString("unit", ""),
                div <= 0 ? 1 : div,
                s.getInt("decimals", 0),
                Math.max(race ? 1 : 0, s.getDouble("target", 1)),
                refs);
    }

    double display(long raw) {
        double v = raw / divisor;
        double f = Math.pow(10, decimals);
        return Math.floor(v * f) / f;
    }

    String fmt(long raw) {
        double v = display(raw);
        String s = decimals == 0 ? String.valueOf((long) v) : String.format(Locale.GERMANY, "%." + decimals + "f", v);
        return unit.isEmpty() ? s : s + " " + unit;
    }
}
