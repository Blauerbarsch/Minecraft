package dev.opwatch;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Maps a command line to a category using the "categories" section of config.yml. */
final class CommandCategorizer {

    static final String DEFAULT = "sonstiges";

    private final Map<String, String> byCommand = new HashMap<>();
    private final Map<String, String> labels = new LinkedHashMap<>();
    private final Set<String> highlight = new HashSet<>();
    private final Set<String> ignored = new HashSet<>();
    private String worldEditCategory = null;

    CommandCategorizer(FileConfiguration cfg) {
        ConfigurationSection sec = cfg.getConfigurationSection("categories");
        if (sec != null) {
            for (String cat : sec.getKeys(false)) {
                String label = sec.getString(cat + ".label", cat);
                labels.put(cat, label);
                for (String c : sec.getStringList(cat + ".commands")) {
                    String k = c.toLowerCase(Locale.ROOT).trim();
                    if (k.equals("//*")) worldEditCategory = cat;
                    else byCommand.put(k, cat);
                }
            }
        }
        labels.putIfAbsent(DEFAULT, "Sonstiges");
        for (String c : cfg.getStringList("highlight-commands")) highlight.add(c.toLowerCase(Locale.ROOT));
        for (String c : cfg.getStringList("ignore-commands")) ignored.add(c.toLowerCase(Locale.ROOT));
    }

    Map<String, String> labels() { return labels; }

    /** Base command without slash and namespace, e.g. "/minecraft:give x" -> "give". */
    static String base(String line) {
        String s = line.trim();
        if (s.startsWith("/")) s = s.substring(1);
        int sp = s.indexOf(' ');
        String b = (sp < 0 ? s : s.substring(0, sp)).toLowerCase(Locale.ROOT);
        int colon = b.indexOf(':');
        if (colon >= 0 && colon < b.length() - 1) b = b.substring(colon + 1);
        return b;
    }

    boolean isIgnored(String line) {
        return ignored.contains(base(line));
    }

    String categorize(String line) {
        String s = line.trim();
        if (s.startsWith("/")) s = s.substring(1);
        // WorldEdit style commands: //set, //copy ...
        if (s.startsWith("/") && worldEditCategory != null) return worldEditCategory;
        String b = base(s);
        // "execute ... run <cmd>" -> categorize the command actually being run
        if (b.equals("execute")) {
            int idx = s.indexOf(" run ");
            if (idx >= 0) {
                String inner = categorize(s.substring(idx + 5));
                if (!inner.equals(DEFAULT)) return inner;
            }
        }
        return byCommand.getOrDefault(b, DEFAULT);
    }

    boolean isHighlighted(String line) {
        String s = line.trim();
        if (s.startsWith("/")) s = s.substring(1);
        String b = base(s);
        if (highlight.contains(b)) return true;
        if (b.equals("execute")) {
            int idx = s.indexOf(" run ");
            if (idx >= 0) return isHighlighted(s.substring(idx + 5));
        }
        return false;
    }

    static List<String> sortedKeys(Map<String, String> m) {
        return new java.util.ArrayList<>(m.keySet());
    }
}
