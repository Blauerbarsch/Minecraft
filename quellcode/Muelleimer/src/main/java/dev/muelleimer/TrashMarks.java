package dev.muelleimer;

import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Remembers which blocks are trash cans. The positions are stored in the chunk itself
 * (PersistentDataContainer of the chunk), so they are saved with the world – no extra file,
 * and they survive restarts and backups together with the world.
 */
final class TrashMarks {

    private final NamespacedKey key;

    TrashMarks(NamespacedKey key) {
        this.key = key;
    }

    private static String pos(Block b) {
        return b.getX() + "," + b.getY() + "," + b.getZ();
    }

    /** "x,y,z;x,y,z" -> set */
    static Set<String> parse(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s == null || s.isBlank()) return out;
        for (String p : s.split(";")) if (p.matches("-?\\d+,-?\\d+,-?\\d+")) out.add(p);
        return out;
    }

    private Set<String> read(Chunk c) {
        return parse(c.getPersistentDataContainer().get(key, PersistentDataType.STRING));
    }

    private void write(Chunk c, Set<String> set) {
        PersistentDataContainer pdc = c.getPersistentDataContainer();
        if (set.isEmpty()) pdc.remove(key);
        else pdc.set(key, PersistentDataType.STRING, String.join(";", set));
    }

    boolean is(Block b) {
        return read(b.getChunk()).contains(pos(b));
    }

    void mark(Block b) {
        Set<String> s = read(b.getChunk());
        if (s.add(pos(b))) write(b.getChunk(), s);
    }

    void unmark(Block b) {
        Set<String> s = read(b.getChunk());
        if (s.remove(pos(b))) write(b.getChunk(), s);
    }
}
