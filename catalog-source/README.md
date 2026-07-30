# Quelldaten des Kartenkatalogs

Dieses Verzeichnis enthält die **versionierte Eingabe** für den Katalog-Import. Es wird nicht automatisch befüllt.

## Warum nicht automatisch geladen wird

Drei Gründe, alle bewusst:

Der Build bleibt **reproduzierbar**. Zweimal derselbe Commit ergibt zweimal denselben Katalog, unabhängig davon, was die Quelle heute ausliefert.

Der Build bleibt **offline**. Eine App, deren ganzer Sinn Offline-Betrieb ist, sollte sich nicht beim Bauen ans Netz hängen.

Eine Änderung an der Quelle wird **sichtbar**. Sie erscheint als Änderung an `treachery-cards.json.sha256` in einem Commit, statt still in den Katalog zu wandern.

## Dateien

| Datei | Inhalt |
|---|---|
| `treachery-cards.json` | Die Quelldatei, unverändert wie geladen |
| `treachery-cards.json.sha256` | Prüfsumme, wird **vor** jeder Verarbeitung geprüft |
| `provenance.json` | Herkunftsnachweis, vom Werkzeug nach erfolgreicher Prüfung geschrieben |

## Erstmalig oder erneut laden

Ein bewusster, manueller Schritt.

```
curl -L \
  -H "User-Agent: Mozilla/5.0" \
  -o catalog-source/treachery-cards.json \
  https://mtgtreachery.net/rules/oracle/treachery-cards.json
```

Der `User-Agent` ist nicht optional: Ohne browsertypischen Header antwortet die Quelle mit **HTTP 403**.

Danach die Prüfsumme neu schreiben:

```
cd tools/catalog-import && ./gradlew run --args="checksum ../../catalog-source"
```

Beide Änderungen — Quelldatei und Prüfsumme — gehören in **einen** Commit, dessen Nachricht sagt, warum neu geladen wurde. Der Development Log im Vault bekommt einen Eintrag.

## Prüfen

```
cd tools/catalog-import && ./gradlew validate
```

Geprüft werden: Prüfsumme, Kopfdaten, genau 62 Karten mit lückenlosen IDs 1–62, und die Poolverteilung 13 Leader / 18 Guardian / 18 Assassin / 13 Traitor.

Schlägt etwas fehl, bricht das Werkzeug ab und erzeugt **nichts**.

## Herkunft und Rechte

Quelle: `https://mtgtreachery.net/rules/oracle/treachery-cards.json`, gepflegt vom Treachery-Projekt (`api_author`: Stefouch, Tymbaroth).

Die Treachery-Identitäten sind Fan-Content. Magic: The Gathering ist Eigentum von Wizards of the Coast; dieses Projekt steht in keiner Verbindung dazu. Die Nennung in der App erfolgt mit `T-177`.
