# Minecraft-Server-Plugins (Stand 29.10.2026)

## plugins/ – fertige Jars für den plugins-Ordner
| Plugin | Version | Was |
|---|---|---|
| WebConsole | 1.4.1 | Dashboard auf Port 8768: Konsole, Konten, Befehlsmenü, Tabs der anderen Plugins, Karte (BlueMap) |
| OpWatch | 1.2.0 | Überwachung: Commands nach Kategorie, Gamemode, Creative-Items (Tab nur für Admins) |
| ServerStats | 1.3.0 | Statistik, Wall of Shame, Spieler-Seite mit Rüstung/Verzauberungen, `/stats` |
| WochenChallenge | 1.2.0 | Wöchentliche Challenges mit Belohnungen, `/challenge` |
| GraveRestore | 1.3.0 | Inventare aus DeathChest-Logs wiederherstellen, `grab liste` / `grab zurück` (nur Konsole) |
| Muelleimer | 1.1.0 | `/trash`, `/trash undo`, Mülleimer-Block (Kessel + Kaktus) |
| Tageszeit | 1.0.0 | Längere Tage (Vorgabe 30 Min. Tag, 10 Min. Nacht), Schlafen und Onlysleep funktionieren weiter, `/zeit` |

Beim Aktualisieren die alten Jars aus dem plugins-Ordner löschen, die neuen hineinlegen, Server neu starten.
Konfigurationen und Daten bleiben erhalten.

### Neu in dieser Runde: einheitliches Design
Alle Weboberflächen und alle Chat-Meldungen folgen jetzt einem gemeinsamen Stil („Deepslate & Gold“).
Die Schriften sind eingebettet – die Seiten laden nichts mehr von Google. Details: [`design/README.md`](design/README.md).

## design/ – gemeinsames Design (Theme, Schriften, Chat-Stil)
## quellcode/ – Quelltext aller Plugins (je mit README, `gradle build`)
Gebaut wird aus dem Repo heraus, weil jedes Plugin beim Bauen `design/theme.css` einsetzt.

## extras/
- `luckperms/vorlage.json.gz` – nach `plugins/LuckPerms/` kopieren, dann `lp import vorlage.json.gz`
- `luckperms/luckperms-vorlage.txt` – dieselbe Vorlage als Befehlsliste
- `wiederherstellung/` – give-Befehle für das verlorene Inventar vom 25.10. (mit `minecraft:give`)
