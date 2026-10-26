package dev.webconsole;

import java.util.List;
import java.util.Map;

/** What the web part needs from the server. Kept separate so the web part can be tested on its own. */
interface Backend {
    /** Latest status snapshot (TPS, memory, players, command menu ...). Must be safe to call from any thread. */
    Map<String, Object> status();

    /** Runs a console command on the main thread. */
    void runCommand(String user, String ip, String command);

    /** Writes a line to the server log and the audit file. */
    void audit(String user, String ip, String text, boolean toConsole);

    /**
     * Pages of other plugins that joined the dashboard, sorted. Each entry:
     * id, title, access ("member" | "admin"), handler (Function&lt;Map, Map&gt;). See {@link Modules}.
     */
    List<Map<String, Object>> modules();
}
