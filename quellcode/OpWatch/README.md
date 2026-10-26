# OpWatch

Paper-Plugin (26.x) zum Überwachen von Spielern – mit Web-Dashboard.

## Installation
1. `OpWatch-1.0.0.jar` in den `plugins/`-Ordner legen, Server starten.
2. In der Konsole steht das automatisch erzeugte Passwort, außerdem in `plugins/OpWatch/config.yml`.
3. Browser: `http://<server-ip>:8765/` → Benutzer `admin` + Passwort.
   Läuft der Server bei einem Hoster, muss Port 8765 dort freigegeben sein (oder einen freigegebenen Port in der config eintragen).

## Was wird geloggt
| Typ | Inhalt |
|---|---|
| Commands | jeder Spieler-Command mit Kategorie (frei in `config.yml` einstellbar), Position und Gamemode; auch Konsole und Commandblöcke. `execute … run X` wird nach X einsortiert, `//…` = WorldEdit |
| Gamemode | jeder Wechsel (egal ob per Command, Plugin oder F3+F4) |
| Creative-Entnahme | welche Items jemand aus dem Creative-Inventar geholt hat (inkl. Pick-Block), zusammengefasst pro paar Sekunden |
| Creative verlassen | Vergleich Inventar + Enderchest beim Betreten vs. Verlassen von Creative → zeigt, was in Survival „mitgenommen“ wurde |
| Creative-Kiste | was ein Creative-Spieler in Kisten/Shulker/Fässer etc. gelegt oder rausgenommen hat (mit Koordinaten) |
| Creative-Drop | Items, die im Creative auf den Boden geworfen wurden |
| Join / Quit | wer wann online war |

Items aus `creative.notable-items` (Commandblock, Barrier, Elytra, Netherite, Spawn-Eggs …) und Commands aus `highlight-commands` werden rot markiert.

## Dateien
Logs liegen als JSON-Lines unter `plugins/OpWatch/logs/JJJJ-MM-TT.jsonl` (eine Datei pro Tag, im Dashboard downloadbar).
Login-Commands (`/login`, `/register` …) werden standardmäßig **nicht** geloggt.

## Zusammen mit WebConsole
Ist das Plugin **WebConsole** installiert, erscheint diese Seite dort als Tab (ein Login für alles),
und der eigene Webserver startet nicht. Mit `web.standalone: true` läuft er trotzdem zusätzlich.
Im Dashboard sehen die Überwachung nur Admins (änderbar in der WebConsole-Config unter `modules`).

## Selbst bauen
`gradle build` (Java 21+). Die fertige Jar liegt dann in `build/libs/`.
