# Tageszeit

Längere (oder kürzere) Tage und Nächte. Vorgabe: **30 Minuten Tag, 10 Minuten Nacht** (normal: je 10).

## So funktioniert es
Der normale Tagesablauf von Minecraft bleibt an – das Plugin bremst ihn nur. Deshalb funktionieren
Schlafen, Schlaf-Plugins wie Onlysleep und `/time set` wie immer: Springt die Zeit, macht das Plugin
vom neuen Stand aus weiter. Wird es entfernt, läuft die Zeit sofort wieder normal.

- Tag = Sonnenaufgang bis Sonnenuntergang, Nacht = Rest inklusive Dämmerung
- Nur Oberwelten; Nether und End sind ausgenommen, weitere Welten unter `ausgenommen` in der config.yml
- Ist der Tagesablauf per Gamerule ausgeschaltet, steht die Zeit still – das Plugin warnt dann in der Konsole

## Befehle
| Befehl | Wer | Was |
|---|---|---|
| `/zeit` | alle | wie lange es noch hell bzw. dunkel ist |
| `/zeit tag <Minuten>` | OP / `tageszeit.admin` | Länge des Tages (1–600) |
| `/zeit nacht <Minuten>` | OP / `tageszeit.admin` | Länge der Nacht (1–600) |
| `/zeit reload` | OP / `tageszeit.admin` | config.yml neu laden |
