# Gemeinsames Design – „Deepslate & Gold“

Alle Plugins sehen gleich aus: im Browser (WebConsole und alle Tabs darin) und im Chat.
Die Formensprache kommt aus dem Minecraft-Inventar: Flächen mit Fase (hell oben links, dunkel unten rechts),
eingelassene „Slots“ für Eingaben und Werte, Gold als Auswahlfarbe wie die Markierung in der Schnellleiste.

## Dateien

| Datei | Wofür |
|---|---|
| `theme.css` | Farben, Schriften, Flächen, Knöpfe, Chips, Dialoge. **Die einzige Stelle für das Web-Design.** |
| `fonts.css` | Die drei Schriften, eingebettet (nur lateinischer Zeichensatz). Erzeugt von `fonts/build-fonts.py`. |
| `fonts/OFL-*.txt` | Lizenzen der Schriften (SIL Open Font License) |
| `Msg.java` | Vorlage für den Chat-Stil. Liegt als Kopie in jedem Plugin, nur das `package` ist anders. |

## So kommt das Design in die Plugins

Jede Weboberfläche hat im `<head>` die Zeile `<!--THEME-->`. Beim `gradle build` ersetzt `build.gradle.kts`
diese Zeile durch `fonts.css` + `theme.css`. Die fertige Seite im Jar braucht also nichts vom Internet –
keine Schriften von Google (Datenschutz) und sie funktioniert auch ohne Internet im LAN.

Wichtig: Gebaut wird aus dem Repo heraus (`quellcode/<Plugin>/` → `../../design/`).

## Farben

| Name | Wert | Rolle |
|---|---|---|
| `--bg` | `#1e2027` | Tiefenschiefer, Seitengrund |
| `--panel` | `#2b2e37` | erhabene Fläche |
| `--sunken` | `#181a20` | eingelassene Slots (Eingaben, Werte, Log) |
| `--gold` | `#f3c04a` | Marke, Auswahl, Hauptknopf |
| `--ok` / `--warn` / `--err` | Smaragd / Kupfer / Redstone | Zustände |
| `--amethyst` | `#b98cf5` | Admin, Verzauberungen |

Schriften: **Pixelify Sans** für Überschriften und Zahlen, **Rubik** für Text, **JetBrains Mono** für Befehle und Logs.

## Bausteine in `theme.css`

- `.panel` – erhabene Fläche, `.slot-in` – eingelassene Fläche
- `button`, `button.primary` (Gold), `button.danger`, `.btn` für Links als Knopf
- `.chip` (+ `.gold`, `.ok`, `.warn`, `.err`, `.admin`), `.live-dot`
- `.pagehead` mit `.mark`, `.srv`, `.where` – Kopfzeile der Einzelseiten, in der WebConsole automatisch ausgeblendet
- `.bar > i` – Fortschrittsbalken, `dialog`, `#toast`

## Chat

```
[Statistik] Spieler nicht gefunden. Nutzung: /stats [Spieler]
[Challenge] Kiesel_Kai übernimmt die Führung mit 1.322 Blöcke!
  1. Blaubarschbube0 – 1.840 Blöcke
```

| Code | Rolle |
|---|---|
| `§8[§6Name§8]` | Prefix jedes Plugins |
| `§7` | normaler Text |
| `§f` | Werte und Zahlen |
| `§e` | Spielernamen |
| `§b` | Befehle und Links |
| `§a` / `§c` | geschafft / Fehler |
| `§6` | Überschriften |

In Java: `Msg.init("Name")` in `onEnable`, dann `Msg.info/ok/err/head/line/kv(...)` und zum Hervorheben
`Msg.cmd("/befehl")`, `Msg.name(spieler)`, `Msg.val(zahl)`.
