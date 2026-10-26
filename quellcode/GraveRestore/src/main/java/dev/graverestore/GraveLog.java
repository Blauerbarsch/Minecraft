package dev.graverestore;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads the audit files of the grave plugin (audit-YYYY-MM-DD.csv).
 * Every line: "timestamp";"TYPE";"{json}". CREATE_CHEST holds the full inventory of a death,
 * DESTROY_CHEST says what happened to that chest later.
 */
final class GraveLog {

    static final Pattern FILE = Pattern.compile("(?:.*-)?audit-\\d{4}-\\d{2}-\\d{2}\\.csv");

    /** One death = one grave chest. */
    static final class Grave {
        String key, ownerName, ownerId, world, ts;
        long created;
        int x, y, z;
        List<Map<String, Object>> items = new ArrayList<>();
        // filled in when the chest was removed
        String endReason, endBy;
        Boolean endDrops;
        int endRest = -1;
        /** what was still in the chest when it was removed – only that got lost */
        List<Map<String, Object>> endItems = new ArrayList<>();

        /** Items to give back: what was deleted, or (if the chest is unknown/still there) the whole death inventory. */
        List<Map<String, Object>> restoreItems() {
            return endReason != null ? endItems : items;
        }

        static int count(List<Map<String, Object>> l) {
            int n = 0;
            for (Map<String, Object> it : l) n += ((Number) it.getOrDefault("count", 1)).intValue();
            return n;
        }

        int itemCount() {
            int n = 0;
            for (Map<String, Object> it : items) n += ((Number) it.getOrDefault("count", 1)).intValue();
            return n;
        }

        /** Were the items lost (so restoring is fair)? */
        boolean lost() {
            return "EXPIRATION".equals(endReason) && Boolean.FALSE.equals(endDrops) && endRest > 0;
        }

        String status() {
            if (endReason == null) return "Kiste steht noch";
            return switch (endReason) {
                case "EXPIRATION" -> Boolean.FALSE.equals(endDrops)
                        ? (endRest > 0 ? "abgelaufen – " + endRest + " Stapel gelöscht" : "abgelaufen, war leer")
                        : "abgelaufen – Items fallen gelassen";
                case "PLAYER" -> "geleert von " + (endBy == null ? "?" : endBy);
                case "BREAK" -> "abgebaut von " + (endBy == null ? "?" : endBy);
                default -> endReason.toLowerCase();
            };
        }
    }

    private GraveLog() {}

    /** All audit files below the given folders (a few levels deep). */
    static List<File> findFiles(List<File> roots, Logger log) {
        List<File> out = new ArrayList<>();
        for (File root : roots) {
            if (root == null || !root.isDirectory()) continue;
            try (Stream<Path> s = Files.walk(root.toPath(), 4)) {
                s.filter(p -> FILE.matcher(p.getFileName().toString()).matches()).forEach(p -> out.add(p.toFile()));
            } catch (IOException e) {
                log.fine("Ordner nicht lesbar: " + root);
            }
        }
        return out;
    }

    /** Reads all files; the same grave in several files is only counted once. Newest first. */
    @SuppressWarnings("unchecked")
    static List<Grave> read(List<File> files, Logger log) {
        Map<String, Grave> graves = new LinkedHashMap<>();
        List<Object[]> ends = new ArrayList<>();
        for (File f : files) {
            List<String> lines;
            try {
                lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warning("Konnte " + f + " nicht lesen: " + e.getMessage());
                continue;
            }
            for (String line : lines) {
                try {
                    if (line.isBlank()) continue;
                    int a = line.indexOf("\";\"");
                    int b = line.indexOf("\";\"", a + 3);
                    if (a < 0 || b < 0) continue;
                    String ts = line.substring(1, a);
                    String type = line.substring(a + 3, b);
                    String js = line.strip();
                    js = js.substring(b + 3, js.length() - 1);
                    Map<String, Object> d = (Map<String, Object>) Json.parse(js);
                    Map<String, Object> chest = (Map<String, Object>) d.get("chest");
                    Map<String, Object> owner = (Map<String, Object>) chest.get("owner");
                    String key = owner.get("id") + "@" + ((Number) chest.get("createdAt")).longValue();
                    Map<String, Object> inv = (Map<String, Object>) chest.get("inventory");
                    List<Object> content = (List<Object>) inv.getOrDefault("content", List.of());
                    if (type.equals("CREATE_CHEST")) {
                        if (graves.containsKey(key)) continue;
                        Grave g = new Grave();
                        Map<String, Object> loc = (Map<String, Object>) chest.get("location");
                        g.key = key;
                        g.ts = ts;
                        g.created = ((Number) chest.get("createdAt")).longValue();
                        g.ownerName = String.valueOf(owner.get("name"));
                        g.ownerId = String.valueOf(owner.get("id"));
                        g.world = String.valueOf(loc.get("world"));
                        g.x = (int) Math.floor(((Number) loc.get("x")).doubleValue());
                        g.y = (int) Math.floor(((Number) loc.get("y")).doubleValue());
                        g.z = (int) Math.floor(((Number) loc.get("z")).doubleValue());
                        for (Object o : content) if (o instanceof Map<?, ?> m) g.items.add((Map<String, Object>) m);
                        graves.put(key, g);
                    } else if (type.equals("DESTROY_CHEST")) {
                        Map<String, Object> extra = (Map<String, Object>) d.getOrDefault("extra", Map.of());
                        Object by = extra.get("player") instanceof Map<?, ?> pm ? pm.get("name") : null;
                        Object drops = extra.get("item-drops");
                        ends.add(new Object[]{key, String.valueOf(d.get("reason")), by == null ? null : String.valueOf(by),
                                drops instanceof Boolean bo ? bo : null, content.size(), content});
                    }
                } catch (Exception e) {
                    // one broken line must not stop the rest
                }
            }
        }
        for (Object[] e : ends) {
            Grave g = graves.get((String) e[0]);
            if (g == null) continue;
            g.endReason = (String) e[1];
            g.endBy = (String) e[2];
            g.endDrops = (Boolean) e[3];
            g.endRest = (Integer) e[4];
            g.endItems.clear();
            for (Object o : (List<Object>) e[5]) if (o instanceof Map<?, ?> m) g.endItems.add((Map<String, Object>) m);
        }
        List<Grave> l = new ArrayList<>(graves.values());
        l.sort(Comparator.comparingLong((Grave g) -> g.created).reversed());
        return l;
    }

    /** "minecraft:give <player> <id>[component=value,...] <count>" – vanilla give, not the one of Essentials. */
    static String giveCommand(String player, Map<String, Object> item) {
        return "minecraft:give " + player + " " + itemString(item) + " " + ((Number) item.getOrDefault("count", 1)).intValue();
    }

    /** "<id>[component=value,...]" – the format of Minecraft's own item parser (same as in /give). */
    @SuppressWarnings("unchecked")
    static String itemString(Map<String, Object> item) {
        StringBuilder sb = new StringBuilder(String.valueOf(item.get("id")));
        if (item.get("components") instanceof Map<?, ?> comps && !comps.isEmpty()) {
            sb.append('[');
            boolean first = true;
            for (Map.Entry<String, Object> c : ((Map<String, Object>) comps).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(c.getKey()).append('=').append(c.getValue());
            }
            sb.append(']');
        }
        return sb.toString();
    }
}
