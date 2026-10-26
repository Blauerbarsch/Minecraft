package dev.webconsole;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Login sessions. Only a SHA-256 of each token is stored (sessions.json), so a copy of the file
 * can't be used to log in. Sessions survive server restarts and extend themselves while in use.
 */
final class Sessions {

    private static final SecureRandom RND = new SecureRandom();

    private final File file;
    private final long ttl;
    private final Logger log;
    /** sha256(token) -> {user, expires} */
    private final Map<String, Map<String, Object>> map = new HashMap<>();

    Sessions(File file, int days, Logger log) {
        this.file = file;
        this.ttl = Math.max(1, days) * 86_400_000L;
        this.log = log;
    }

    long maxAgeSeconds() { return ttl / 1000; }

    private static String sha(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean wellFormed(String token) {
        return token != null && token.matches("[A-Za-z0-9_-]{43}");
    }

    synchronized String create(String user) {
        byte[] b = new byte[32];
        RND.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("user", user);
        s.put("expires", System.currentTimeMillis() + ttl);
        map.put(sha(token), s);
        save();
        return token;
    }

    /** User name for a valid token, otherwise null. */
    synchronized String user(String token) {
        if (!wellFormed(token)) return null;
        String k = sha(token);
        Map<String, Object> s = map.get(k);
        if (s == null) return null;
        long now = System.currentTimeMillis();
        long exp = ((Number) s.get("expires")).longValue();
        if (exp < now) {
            map.remove(k);
            save();
            return null;
        }
        if (exp - now < ttl / 2) {
            s.put("expires", now + ttl);
            save();
        }
        return (String) s.get("user");
    }

    synchronized void remove(String token) {
        if (wellFormed(token) && map.remove(sha(token)) != null) save();
    }

    synchronized void invalidateUser(String name) {
        if (map.values().removeIf(s -> name.equalsIgnoreCase((String) s.get("user")))) save();
    }

    @SuppressWarnings("unchecked")
    synchronized void load() {
        if (!file.isFile()) return;
        try {
            Map<String, Object> m = (Map<String, Object>) Json.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Object> e : m.entrySet()) {
                Map<String, Object> s = (Map<String, Object>) e.getValue();
                if (((Number) s.get("expires")).longValue() > now) map.put(e.getKey(), s);
            }
        } catch (Exception e) {
            log.warning("sessions.json nicht lesbar – alle müssen sich neu anmelden.");
        }
    }

    private void save() {
        try {
            file.getParentFile().mkdirs();
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            Files.writeString(tmp.toPath(), Json.write(map), StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.warning("sessions.json konnte nicht gespeichert werden: " + e.getMessage());
        }
    }
}
