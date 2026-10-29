package dev.tageszeit;

import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Längere (oder kürzere) Tage und Nächte.
 *
 * <p>Der normale Tagesablauf von Minecraft bleibt AN – das Plugin bremst ihn nur. Jeden Tick schiebt
 * Minecraft die Zeit um 1 weiter; das Plugin setzt sie danach auf den gewünschten Wert (z.&nbsp;B. nur
 * jeden dritten Tick +1). Deshalb funktionieren Schlafen, Schlaf-Plugins wie Onlysleep und /time weiter:
 * Springt die Zeit (Schlaf, Befehl), übernimmt das Plugin einfach den neuen Stand. Steht die Zeit still
 * (Tagesablauf per Gamerule aus), macht es nichts. Wird das Plugin entfernt, läuft die Zeit sofort normal.
 *
 * <p>Tag = Spielzeit 0–12000 (Sonnenaufgang bis Sonnenuntergang), Nacht = 12000–24000 (mit Dämmerung).
 * In Vanilla dauert beides je 10 echte Minuten.
 */
public final class TageszeitPlugin extends JavaPlugin {

    private static final long DAY = 12000, FULL = 24000;
    private static final int TICKS_PER_MINUTE = 20 * 60;

    private double dayMinutes = 30, nightMinutes = 10;
    private final Set<String> excluded = new HashSet<>();

    /** Pro Welt: Zeit, die das Plugin zuletzt gesehen/gesetzt hat, und Bruchteile eines Ticks. */
    private final Map<String, Long> last = new HashMap<>();
    private final Map<String, Double> rest = new HashMap<>();
    /** Pro Welt: wie viele Ticks am Stück die Zeit stillstand (Tagesablauf aus). */
    private final Map<String, Integer> still = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Msg.init("Tageszeit");
        load();
        getServer().getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        getServer().getScheduler().runTaskLater(this, () -> {
            for (World w : worlds())
                if (stopped(w)) getLogger().warning("In " + w.getName() + " steht die Zeit still – der Tagesablauf ist per Gamerule ausgeschaltet."
                        + " Tageszeit kann dort erst wirken, wenn er wieder läuft.");
        }, 100L);
    }

    private void load() {
        reloadConfig();
        dayMinutes = clamp(getConfig().getDouble("tag-minuten", 30));
        nightMinutes = clamp(getConfig().getDouble("nacht-minuten", 10));
        excluded.clear();
        for (String s : getConfig().getStringList("ausgenommen")) excluded.add(s.toLowerCase(Locale.ROOT));
        last.clear();
        rest.clear();
        getLogger().info("Tag " + fmt(dayMinutes) + " Min., Nacht " + fmt(nightMinutes) + " Min.");
    }

    private static double clamp(double minutes) {
        return Math.max(1, Math.min(600, minutes));
    }

    private List<World> worlds() {
        List<World> out = new ArrayList<>();
        for (World w : getServer().getWorlds()) {
            if (w.getEnvironment() != World.Environment.NORMAL) continue;   // Nether/End haben keinen Tag
            if (excluded.contains(w.getName().toLowerCase(Locale.ROOT))) continue;
            out.add(w);
        }
        return out;
    }

    /** Spielzeit-Ticks pro echtem Tick in dieser Phase (Vanilla = 1). */
    private double rate(long dayTime) {
        double minutes = dayTime < DAY ? dayMinutes : nightMinutes;
        return (FULL / 2.0) / (minutes * TICKS_PER_MINUTE);
    }

    private void tick() {
        for (World w : worlds()) {
            String k = w.getName();
            long now = w.getFullTime();
            Long before = last.get(k);
            if (before == null || now != before + 1) {
                // erster Tick, Zeitsprung (Schlafen, /time) oder Zeit steht still: nur merken
                still.put(k, before != null && now == before ? still.getOrDefault(k, 0) + 1 : 0);
                last.put(k, now);
                rest.put(k, 0.0);
                continue;
            }
            still.put(k, 0);
            double acc = rest.getOrDefault(k, 0.0) + rate(Math.floorMod(before, FULL));
            long step = (long) Math.floor(acc);
            rest.put(k, acc - step);
            long target = before + step;
            if (target != now) w.setFullTime(target);
            last.put(k, target);
        }
    }

    private boolean stopped(World w) {
        return still.getOrDefault(w.getName(), 0) > 40;
    }

    // ====================================================================== /zeit

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("tag") || sub.equals("nacht") || sub.equals("reload")) {
            if (!sender.hasPermission("tageszeit.admin")) { Msg.err(sender, "Das dürfen nur Admins."); return true; }
            if (sub.equals("reload")) {
                load();
                Msg.ok(sender, "Neu geladen: Tag " + fmt(dayMinutes) + " Min., Nacht " + fmt(nightMinutes) + " Min.");
                return true;
            }
            double v;
            try {
                v = Double.parseDouble(args.length > 1 ? args[1].replace(',', '.') : "x");
            } catch (NumberFormatException e) {
                Msg.err(sender, "Nutzung: " + Msg.cmd("/zeit " + sub + " <Minuten>") + Msg.ERR + " – z. B. " + Msg.cmd("/zeit " + sub + " 30"));
                return true;
            }
            if (v < 1 || v > 600) { Msg.err(sender, "Bitte zwischen 1 und 600 Minuten."); return true; }
            getConfig().set(sub.equals("tag") ? "tag-minuten" : "nacht-minuten", v);
            saveConfig();
            load();
            Msg.ok(sender, (sub.equals("tag") ? "Ein Tag" : "Eine Nacht") + " dauert jetzt " + fmt(v) + " Minuten.");
            return true;
        }

        World w = sender instanceof Player p ? p.getWorld() : (worlds().isEmpty() ? null : worlds().get(0));
        if (w == null || w.getEnvironment() != World.Environment.NORMAL) {
            Msg.info(sender, "Hier gibt es keinen Tag und keine Nacht.");
        } else if (excluded.contains(w.getName().toLowerCase(Locale.ROOT))) {
            Msg.info(sender, "In dieser Welt läuft die Zeit normal.");
        } else if (stopped(w)) {
            Msg.info(sender, "Die Zeit steht gerade still – der Tagesablauf ist ausgeschaltet.");
        } else {
            long t = Math.floorMod(w.getFullTime(), FULL);
            boolean day = t < DAY;
            long ticksLeft = (day ? DAY : FULL) - t;
            double seconds = ticksLeft / rate(t) / 20.0;
            String left = seconds < 90 ? "knapp 1 Minute" : Math.round(seconds / 60) + " Minuten";
            Msg.info(sender, day ? "Noch " + Msg.val(left) + " hell." : "Noch " + Msg.val(left) + " Nacht, dann wird es hell.");
        }
        Msg.line(sender, "Ein Tag dauert " + Msg.val(fmt(dayMinutes) + " Min.") + ", eine Nacht " + Msg.val(fmt(nightMinutes) + " Min."));
        if (sender.hasPermission("tageszeit.admin"))
            Msg.line(sender, "Ändern: " + Msg.cmd("/zeit tag <Minuten>") + ", " + Msg.cmd("/zeit nacht <Minuten>"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1 && sender.hasPermission("tageszeit.admin"))
            for (String s : new String[]{"tag", "nacht", "reload"}) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
        if (args.length == 2 && (args[0].equalsIgnoreCase("tag") || args[0].equalsIgnoreCase("nacht")))
            out.addAll(List.of("10", "20", "30", "45", "60"));
        return out;
    }

    private static String fmt(double minutes) {
        return minutes == Math.rint(minutes) ? String.valueOf((long) minutes) : String.valueOf(minutes).replace('.', ',');
    }
}
