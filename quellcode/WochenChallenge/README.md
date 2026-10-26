# WochenChallenge

Jede Woche eine neue Challenge, mit Countdown-Seite, Bestenliste, Belohnungen und Hall of Fame.

- Jar in `plugins/` legen, Server starten, `http://<server-ip>:8767/` öffnen.
- Standard: Woche endet sonntags 20:00 Uhr, dann werden Platz 1–3 belohnt und die nächste Challenge ausgelost.
- 16 Challenges sind vorbereitet (Angeln, Diamanten, Zombies, Elytra-Rennen, Netherite-Rennen …). Eigene in `config.yml` anlegen,
  jede Minecraft-Statistik geht (`FISH_CAUGHT`, `MINE_BLOCK:IRON_ORE`, `KILL_ENTITY:SPIDER`, `PICKUP:TRIDENT` …).
- Es zählt nur, was im Survival/Adventure passiert. Wer in der Woche im Creative war, wird markiert
  (oder mit `creative-rule: disqualify` aus der Wertung genommen).
- `/challenge` zeigt Stand und eigenen Platz.
- Nur über die Server-Konsole: `challenge next` (Woche sofort auswerten), `challenge set <id>` (wechseln ohne Wertung), `challenge list`.

## Zusammen mit WebConsole
Ist das Plugin **WebConsole** installiert, erscheint diese Seite dort als Tab (ein Login für alles),
und der eigene Webserver startet nicht. Mit `web.standalone: true` läuft er trotzdem zusätzlich.

Selbst bauen: `gradle build` (Java 21+).
