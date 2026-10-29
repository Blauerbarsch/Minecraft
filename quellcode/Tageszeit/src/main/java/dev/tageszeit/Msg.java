package dev.tageszeit;

import org.bukkit.command.CommandSender;

/**
 * Gemeinsamer Chat-Stil aller Server-Plugins ("Deepslate &amp; Gold").
 * Vorlage: design/Msg.java im Repo – in jedes Plugin kopiert, nur das package unterscheidet sich.
 *
 * <pre>
 *   [Name] Text …          Prefix: dunkelgraue Klammern, Name in Gold
 *   §7 Text   §f Wert/Zahl   §e Spielername   §b Befehl/Link   §a geschafft   §c Fehler   §6 Überschrift
 * </pre>
 * Folgezeilen ohne Prefix rücken mit "  " ein.
 * Nutzt bewusst nur CommandSender.sendMessage(String), damit kein Plugin neue API-Abhängigkeiten bekommt.
 */
final class Msg {
    private Msg() {}

    static final String TEXT = "§7", VALUE = "§f", NAME = "§e", CMD = "§b", OK = "§a", ERR = "§c", HEAD = "§6";

    private static String prefix = "§8[§6Server§8] §7";

    /** Einmal in onEnable aufrufen, z. B. Msg.init("Statistik"). */
    static void init(String name) {
        prefix = "§8[§6" + name + "§8] " + TEXT;
    }

    static String prefix() { return prefix; }

    /** Normale Mitteilung mit Prefix. */
    static void info(CommandSender to, String text) { send(to, prefix + text); }

    /** Erfolg (grün) mit Prefix. */
    static void ok(CommandSender to, String text) { send(to, prefix + OK + text); }

    /** Fehler (rot) mit Prefix. */
    static void err(CommandSender to, String text) { send(to, prefix + ERR + text); }

    /** Überschrift mit Prefix, z. B. für /stats oder /challenge. */
    static void head(CommandSender to, String title) { send(to, prefix + HEAD + "§l" + title); }

    /** Folgezeile unter einer Überschrift, ohne Prefix, eingerückt. */
    static void line(CommandSender to, String text) { send(to, "  " + TEXT + text); }

    /** "Label: Wert" als Folgezeile. */
    static void kv(CommandSender to, String label, String value) { line(to, label + ": " + VALUE + value); }

    /** Befehl im Text hervorheben: Msg.cmd("/trash undo"). */
    static String cmd(String command) { return CMD + command + TEXT; }

    /** Spielername im Text hervorheben. */
    static String name(String player) { return NAME + player + TEXT; }

    /** Wert/Zahl im Text hervorheben. */
    static String val(Object value) { return VALUE + value + TEXT; }

    static void send(CommandSender to, String text) {
        to.sendMessage(text);
    }
}
