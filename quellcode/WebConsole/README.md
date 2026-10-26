# WebConsole

Server-Konsole im Browser und gemeinsames Dashboard für OpWatch, ServerStats und WochenChallenge.
Die anderen Plugins bleiben eigenständig – sind sie installiert, erscheinen sie hier als Tabs:

| Tab | Plugin | sichtbar für |
|---|---|---|
| Konsole | WebConsole | alle (Befehle nur Admins) |
| Statistik | ServerStats | alle |
| Spieler | ServerStats | alle (Koordinaten + Inventar nur Admins) |
| Challenge | WochenChallenge | alle |
| Überwachung | OpWatch | nur Admins |
| Karte | BlueMap / squaremap (extern) | alle |
| Konten | WebConsole | nur Admins |

Wer was sieht, steht in `config.yml` unter `modules` (`member`, `admin` oder `off`).

## Live-Karte (und andere Web-Seiten)
Unter `external-tabs` in der config. Standard: Tab „Karte“, der BlueMap auf `127.0.0.1:8100` über die
WebConsole weiterreicht – die Karte ist damit hinter dem Login und es muss nur Port 8768 offen sein.
In BlueMap (`plugins/BlueMap/webserver.conf`) dafür `ip: "127.0.0.1"` setzen. Für squaremap Port 8080 eintragen.
Tab nicht gewünscht: `external-tabs: []`.

## Befehlsmenü
Links neben der Eingabezeile öffnet „Befehle“ ein Menü mit fertigen Befehlen (Spieler kicken, Spielmodus,
Wetter, Whitelist, Challenge auswerten …). Felder wie Spieler oder Anzahl werden abgefragt, der fertige
Befehl wird vorher angezeigt, kritische Befehle fragen nach. Eigene Einträge unter `command-menu` in der config:
```yaml
- label: "Spieler heilen"
  command: "effect give {player} instant_health 1 10"
```
Platzhalter: `{player}`, `{player:Ziel}`, `{text:Grund}` (mit `?` optional), `{number:Anzahl:64}`,
`{choice:Modus:survival=Survival,creative=Creative}`. `confirm: true` fragt vor dem Ausführen.

## Rollen

- **Admin**: sieht alles, führt Befehle aus, verwaltet Konten
- **Member**: sieht Konsole, Statistik, Spieler und Challenge (IP-Adressen sind für Member ausgeblendet)

## Start
1. Jar in `plugins/` legen, Server starten.
2. In der Server-Konsole steht einmalig das Passwort für das Konto `admin`.
3. `http://<server-ip>:8768/` öffnen, einloggen, unter „Passwort ändern“ ein eigenes setzen.
4. Freunde im Tab „Konten“ anlegen – das Passwort wird einmal angezeigt, dann weitergeben.

Passwort vergessen? In der Server-Konsole: `webconsole reset admin`.

## Konsolen-Befehle (nur Server-Konsole, nicht im Spiel)
```
webconsole list
webconsole add <name> <admin|member>
webconsole reset <name>
webconsole role <name> <admin|member>
webconsole remove <name>
```
Erzeugte Passwörter erscheinen nur in der echten Konsole, nie in der Web-Konsole.

## Sicherheit
- Passwörter werden nur als PBKDF2-Hash gespeichert (`users.json`).
- Nach 5 falschen Logins wird die IP gesperrt (30 s, steigt bis 15 Min.).
- Jeder Web-Befehl erscheint als `[Web] name: /befehl` in der Konsole und in `plugins/WebConsole/audit.log`.
- Zeilen mit `/login`, `/register` usw. werden in der Web-Konsole nie angezeigt (`hide-lines-containing`).
- **Kein HTTPS**: Über das offene Internet gehen Passwörter unverschlüsselt durchs Netz. Für Zugriff von außen
  einen Reverse-Proxy mit HTTPS (z.B. Caddy) oder ein VPN wie Tailscale davorschalten,
  oder `web.bind: "127.0.0.1"` setzen und nur lokal nutzen.

## Für andere Plugins
Jedes Plugin kann eine Seite beisteuern, ohne WebConsole als Abhängigkeit: eine `java.util.Map` beim
ServicesManager unter `Map.class` registrieren (`webhub`, `id`, `title`, `order`, `access`, `handler`).
Details in `Modules.java`, Beispiel in `HubLink.java` der anderen Plugins.

Selbst bauen: `gradle build` (Java 21+).
