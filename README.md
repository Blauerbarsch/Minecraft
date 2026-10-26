# Minecraft-Server-Plugins (Stand 26.10.2026)

## plugins/ – fertige Jars für den plugins-Ordner
| Plugin | Version | Was |
|---|---|---|
| WebConsole | 1.3.0 | Dashboard auf Port 8768: Konsole, Konten, Befehlsmenü, Tabs der anderen Plugins, Karte (BlueMap) |
| OpWatch | 1.1.0 | Überwachung: Commands nach Kategorie, Gamemode, Creative-Items (Tab nur für Admins) |
| ServerStats | 1.2.0 | Statistik, Wall of Shame, Spieler-Seite mit Rüstung/Verzauberungen, `/stats` |
| WochenChallenge | 1.1.0 | Wöchentliche Challenges mit Belohnungen, `/challenge` |
| GraveRestore | 1.2.0 | Inventare aus DeathChest-Logs wiederherstellen, `grab liste` / `grab zurück` (nur Konsole) |
| Muelleimer | 1.0.1 | `/trash`, `/trash undo`, Mülleimer-Block (Kessel + Kaktus) |

## quellcode/ – Quelltext aller Plugins (je mit README, `gradle build`)
## extras/
- `luckperms/vorlage.json.gz` – nach `plugins/LuckPerms/` kopieren, dann `lp import vorlage.json.gz`
- `luckperms/luckperms-vorlage.txt` – dieselbe Vorlage als Befehlsliste
- `wiederherstellung/` – give-Befehle für das verlorene Inventar vom 25.10. (mit `minecraft:give`)
