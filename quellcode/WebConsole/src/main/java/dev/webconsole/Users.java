package dev.webconsole;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Web accounts, stored in users.json. Passwords are kept only as PBKDF2-SHA256 hashes.
 * Hashing is slow on purpose, so it never runs while holding the lock.
 */
final class Users {

    static final String ADMIN = "admin";
    static final String MEMBER = "member";
    private static final int ITERATIONS = 600_000;
    private static final SecureRandom RND = new SecureRandom();
    private static final byte[] DUMMY_SALT = new byte[16];

    private final File file;
    private final Logger log;
    /** lower-case name -> {name, role, salt, hash, iterations, created} */
    private final Map<String, Map<String, Object>> users = new LinkedHashMap<>();

    Users(File file, Logger log) {
        this.file = file;
        this.log = log;
    }

    // ------------------------------------------------------------------ helpers

    static boolean validName(String n) {
        return n != null && n.matches("[A-Za-z0-9_.-]{2,24}");
    }

    static boolean validRole(String r) {
        return ADMIN.equals(r) || MEMBER.equals(r);
    }

    static String randomPassword() {
        String chars = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 14; i++) sb.append(chars.charAt(RND.nextInt(chars.length())));
        return sb.toString();
    }

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private static byte[] derive(String pw, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pw.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> hashed(String pw) {
        byte[] salt = new byte[16];
        RND.nextBytes(salt);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("salt", Base64.getEncoder().encodeToString(salt));
        m.put("hash", Base64.getEncoder().encodeToString(derive(pw, salt, ITERATIONS)));
        m.put("iterations", ITERATIONS);
        return m;
    }

    // ------------------------------------------------------------------ storage

    @SuppressWarnings("unchecked")
    synchronized void load() {
        if (!file.isFile()) return;
        try {
            Map<String, Object> root = (Map<String, Object>) Json.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            for (Object o : (List<Object>) root.getOrDefault("users", List.of())) {
                Map<String, Object> u = (Map<String, Object>) o;
                users.put(key((String) u.get("name")), u);
            }
        } catch (Exception e) {
            log.severe("users.json konnte nicht gelesen werden: " + e.getMessage());
        }
    }

    private void save() {
        try {
            file.getParentFile().mkdirs();
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("users", new ArrayList<>(users.values()));
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            Files.writeString(tmp.toPath(), Json.write(root), StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.severe("users.json konnte nicht gespeichert werden: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ queries

    synchronized boolean isEmpty() { return users.isEmpty(); }

    synchronized String role(String name) {
        Map<String, Object> u = users.get(key(name));
        return u == null ? null : (String) u.get("role");
    }

    synchronized String displayName(String name) {
        Map<String, Object> u = users.get(key(name));
        return u == null ? null : (String) u.get("name");
    }

    synchronized List<Map<String, Object>> list() {
        List<Map<String, Object>> l = new ArrayList<>();
        for (Map<String, Object> u : users.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", u.get("name"));
            m.put("role", u.get("role"));
            m.put("created", u.get("created"));
            l.add(m);
        }
        return l;
    }

    private int admins() {
        int n = 0;
        for (Map<String, Object> u : users.values()) if (ADMIN.equals(u.get("role"))) n++;
        return n;
    }

    /** Returns the account name (correct case) or null. Takes the same time whether the user exists or not. */
    String verify(String name, String pw) {
        if (name == null || pw == null || pw.length() > 200) return null;
        Map<String, Object> u;
        synchronized (this) {
            Map<String, Object> x = users.get(key(name));
            u = x == null ? null : new LinkedHashMap<>(x);
        }
        byte[] salt = u == null ? DUMMY_SALT : Base64.getDecoder().decode((String) u.get("salt"));
        int it = u == null ? ITERATIONS : ((Number) u.get("iterations")).intValue();
        byte[] got = derive(pw, salt, it);
        if (u == null) return null;
        return MessageDigest.isEqual(got, Base64.getDecoder().decode((String) u.get("hash"))) ? (String) u.get("name") : null;
    }

    // ------------------------------------------------------------------ changes (throw IllegalArgumentException with a user-facing message)

    /** Creates an account with a random password and returns that password. */
    String create(String name, String role) {
        if (!validName(name)) throw new IllegalArgumentException("Name: 2–24 Zeichen, nur Buchstaben, Zahlen, _ . -");
        if (!validRole(role)) throw new IllegalArgumentException("Rolle muss admin oder member sein.");
        synchronized (this) {
            if (users.containsKey(key(name))) throw new IllegalArgumentException("Das Konto „" + name + "“ gibt es schon.");
        }
        String pw = randomPassword();
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("name", name);
        u.put("role", role);
        u.putAll(hashed(pw));
        u.put("created", System.currentTimeMillis());
        synchronized (this) {
            if (users.containsKey(key(name))) throw new IllegalArgumentException("Das Konto „" + name + "“ gibt es schon.");
            users.put(key(name), u);
            save();
        }
        return pw;
    }

    void setPassword(String name, String pw) {
        if (pw == null || pw.length() < 8) throw new IllegalArgumentException("Das Passwort braucht mindestens 8 Zeichen.");
        if (pw.length() > 128) throw new IllegalArgumentException("Das Passwort darf höchstens 128 Zeichen haben.");
        Map<String, Object> h = hashed(pw);
        synchronized (this) {
            Map<String, Object> u = users.get(key(name));
            if (u == null) throw new IllegalArgumentException("Das Konto gibt es nicht.");
            u.putAll(h);
            save();
        }
    }

    String resetPassword(String name) {
        String pw = randomPassword();
        setPassword(name, pw);
        return pw;
    }

    synchronized void setRole(String name, String role) {
        if (!validRole(role)) throw new IllegalArgumentException("Rolle muss admin oder member sein.");
        Map<String, Object> u = users.get(key(name));
        if (u == null) throw new IllegalArgumentException("Das Konto gibt es nicht.");
        if (ADMIN.equals(u.get("role")) && MEMBER.equals(role) && admins() <= 1)
            throw new IllegalArgumentException("Es muss mindestens ein Admin übrig bleiben.");
        u.put("role", role);
        save();
    }

    synchronized void delete(String name) {
        Map<String, Object> u = users.get(key(name));
        if (u == null) throw new IllegalArgumentException("Das Konto gibt es nicht.");
        if (ADMIN.equals(u.get("role")) && admins() <= 1)
            throw new IllegalArgumentException("Der letzte Admin kann nicht gelöscht werden.");
        users.remove(key(name));
        save();
    }
}
