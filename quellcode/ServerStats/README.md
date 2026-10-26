# ServerStats

Statistik-Seite für alle auf dem Server – Bestenlisten, Wall of Shame, letzte Tode, Spielerprofile.

- Jar in `plugins/` legen, Server starten, `http://<server-ip>:8766/` öffnen (Port in `config.yml`).
- Bestenlisten nutzen die eingebauten Minecraft-Statistiken, zählen also **seit Weltbeginn**.
- Todesursachen (Lava, Fall, Creeper …) kennt Minecraft nicht – die zählt das Plugin ab Installation selbst (`deaths.jsonl`).
- `/stats [Spieler]` zeigt die wichtigsten Werte im Chat.
- Die Seite zeigt keine Koordinaten. Spieler ausblenden: `hidden-players` in der config.
- Köpfe kommen von mc-heads.net (lädt der Browser der Besucher).

## Seite „Spieler“
Pro Spieler: Leben, Hunger, Erfahrung, Rüstungswert, aktuelle Ausrüstung mit Verzauberungen, Haltbarkeit und
Verzierungen, aktive Effekte, wichtige Werte und der letzte Tod. Wer offline ist, wird mit dem Stand beim
letzten Ausloggen gezeigt (`players.json`). Koordinaten, Inventar und Endertruhe sehen standardmäßig nur
Admins – einstellbar unter `players-page` in der config. Ohne WebConsole liegt die Seite unter `/spieler/`.

## Zusammen mit WebConsole
Ist das Plugin **WebConsole** installiert, erscheint diese Seite dort als Tab (ein Login für alles),
und der eigene Webserver startet nicht. Mit `web.standalone: true` läuft er trotzdem zusätzlich.

Selbst bauen: `gradle build` (Java 21+).
