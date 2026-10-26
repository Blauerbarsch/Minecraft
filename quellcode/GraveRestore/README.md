# GraveRestore

Stellt Inventare aus den Audit-Logs des Grab-Plugins 1:1 wieder her (Verzauberungen, Haltbarkeit,
Lore, Shulker-Inhalt …). Nur über die Server-Konsole oder die Web-Konsole nutzbar.

    grab liste [Spieler]           letzte Tode mit Nummer und Status
    grab zurück <Spieler> [Nr]     Inventar zurückgeben (Nr 1 = letzter Tod)
    grab zurück <Spieler> <Nr> spezial   nur Items mit Zusatzdaten (verzaubert, beschädigt, Shulker-Inhalt …)

- Items werden mit Minecrafts eigenem Item-Parser erzeugt (derselbe wie bei /give, aber ohne Umweg über
  Essentials) und direkt ins Inventar gelegt. Klappt ein Item nicht, steht das in der Antwort – es wird
  nie „zurückgegeben“ gemeldet, was nicht angekommen ist.
- Sucht `audit-JJJJ-MM-TT.csv` im plugins-Ordner (und darunter) und in `logs/`.
- Gibt nur zurück, was beim Ablaufen der Kiste wirklich gelöscht wurde – nichts, was man schon rausgeholt hatte.
- Merkt sich, was schon zurückgegeben wurde (`restored.json`), damit nichts doppelt kommt.
  Bewusst trotzdem: `grab zurück <Spieler> <Nr> nochmal`
- Der Spieler muss online sein. XP und die Plätze im Inventar stehen nicht im Log.
