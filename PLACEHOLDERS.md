# Bekannte Platzhalter und nicht implementierte Funktionen

Stand: T-018 umgesetzt, 2026-07-30. Diese Liste ist Teil der Abnahme und wird mit jedem Task fortgeschrieben.

## Platzhalter im Code

| Ort | Was es ist | Ersetzt durch |
|---|---|---|
| `ui/.../StatusActivity.kt` | Statusseite, die den Gerüststand anzeigt. Seit `T-017` **nicht mehr** der Einstiegspunkt — erreichbar über die Kopfzeile des Katalog-Browsers | `T-140` Tischansicht |
| `*/Placeholder.kt` in **sechs** Modulen | Leere `internal object`, damit jedes Modul einen Quellbaum hat und der Modulschnitt baubar ist | jeweils der erste echte Inhalt des Moduls |
| `libs.versions.toml` → `tink-android` | Nur im Katalog geführt, von keinem Modul verwendet | `T-050` Krypto-Fassade |

**Zu löschen:** `catalog/src/main/kotlin/de/myhornets/rise1/catalog/Placeholder.kt`. Das Modul hat seit `T-014` echten Inhalt — die Datei war genau der Platzhalter dafür und hat ihren Zweck erfüllt.

## Erledigt: die Kartenbilder

Hier stand, dass kein einziges Kartenbild im Repository liegt. **Seit dem 2026-07-30 liegen alle 62 dort** — unter `catalog/src/main/assets/cards`, im APK also `assets/cards/`.

```
Dateien:      62 · Namen deckungsgleich mit den 62 image_asset-Werten
Gesamtgröße:  6 940 577 Bytes = 6,62 MiB
je Datei:     94 – 128 KiB
```

Damit sind auch die Zahlen nachgetragen, die `T-012` offen gelassen hatte. Der Bildlader in `ui/.../browser/KatalogZugriff.kt` liest aus `cards/`.

Der Katalog-Browser bleibt trotzdem nachsichtig: Fehlt ein Bild, zeigt die Liste den Anfangsbuchstaben und die Einzelansicht eine Platzhalterfläche; **ein** Hinweis oben in der Liste sagt, warum. Eine fehlende Datei darf den Katalog nicht unbenutzbar machen.

## Erledigt: die Quelldatei

`catalog-source/treachery-cards.json` und `catalog-source/treachery-cards.json.sha256` **liegen seit dem 2026-07-30 im Repository**. Hier stand vorher, dass sie fehlen. Das Laden bleibt ein ausdrücklicher manueller Schritt (`catalog-source/README.md`, mit `User-Agent` — sonst antwortet die Quelle mit HTTP 403); es passiert nicht beim Bauen.

## Nicht implementiert

Sechs der neun Module sind leer. Was in welches gehört und ab welchem Epic, steht in der Tabelle im `README.md`.

`catalog` hat seit `T-014` Inhalt: fünf Room-Entities auf dem Schema aus `T-013`, ein DAO für Anzeige, Filter und Suche, `CatalogAsset.open` als einziger Weg zur ausgelieferten `catalog.db` und seit `T-016` der Regeltext-Zerleger. `ui` hat seit `T-017` den Katalog-Browser.

Konkret gibt es weiterhin **keine** Kryptografie, **keinen** Netzwerkverkehr, **keine** Rollenverteilung und **keinen** Spielzustand. Die APK zeigt den Katalog und tut sonst nichts.

## Bewusst offene Entscheidungen

`D-003` (Zugzählung) ist noch nicht getroffen. Sie entscheidet über die Event-Typen `turn_started` / `turn_ended` und über das Feld `match_state.turn_number`. Sie blockiert `T-025` und `T-146`, aber nichts in T-001 bis T-005.

## Nicht geprüfte Annahmen

**Die Versionen in `gradle/libs.versions.toml`** — `agp`, `kotlin`, `tink` — konnten nicht gegen die Repositories geprüft werden. Siehe `README.md`, Abschnitt „Wenn der erste Build scheitert".

**Die Version `gson 2.11.0`** im Import-Werkzeug konnte ebenfalls nicht gegen Maven Central geprüft werden. Sie steht an genau einer Stelle: `tools/catalog-import/build.gradle.kts`.

**Vom Import-Werkzeug sind 17 Tests gelaufen, 72 nicht.** Der Stand vom 2026-07-30: `build` war grün, damals mit 17 Tests. Seither sind `T-011` bis `T-015` dazugekommen — 89 insgesamt. Ob die neuen laufen, ist offen; `build` nennt keine Zahl, und nach der Regel aus `Testing.md` zählt die Zahl aus `build/test-results/test/`, nicht der Exit-Code.

**Die Versionen aus `T-014`** — `ksp 2.2.20-2.0.4`, `room 2.8.4` und die drei `androidx-test-*` — sind ebenfalls ungeprüft. Sie stehen je an genau einer Stelle in `gradle/libs.versions.toml`, samt Begründung und Rückfall im Kopf der Datei. Bei KSP gilt eine zusätzliche Bedingung: Das Präfix **ist** die Kotlin-Version, eine `2.2.21-*` bricht gegen `kotlin = 2.2.20`.

**Der Room-Teil aus `T-014` ist einmal übersetzt worden — mit dem Stand von vier Entities.** KSP hat gearbeitet, `catalog/schemas/…/1.json` liegt vor, und Room erzeugte daraus **wortgleich** die vorhergesagten `CREATE TABLE`-Anweisungen. `roomStamp` ist gelaufen; `catalog.db` ist um genau eine SQLite-Seite gewachsen. Damit sind `ksp 2.2.20-2.0.4` und `room 2.8.4` bestätigt.

**Dieser Stand ist seit `T-015` überholt.** Die fünfte Entity ändert den `identityHash`; Export und ausgelieferte Datei müssen neu erzeugt werden. Nicht belegt sind weiterhin `checkAll` seit `T-015` und der instrumentierte Abnahmetest.

**Der Katalog-Browser aus `T-017` wurde nie übersetzt und nie auf einem Gerät gestartet.** Compose-Code ist in diesem Projekt bisher nur für die Platzhalterseite gebaut worden; das hier ist die erste größere Fläche. Neu und ungeprüft ist dabei genau eine Koordinate: `compose-foundation`, versionslos aus der BOM.

**Die 27 instrumentierten Tests in `:catalog` sind nie gelaufen.** Sie brauchen Gerät oder Emulator: `./gradlew :catalog:connectedDebugAndroidTest`. `checkAll` fährt sie nicht mit.

**Der Stand von `T-015` bis `T-017` wurde nie gebaut.** Die Umgebung, in der dieser Quellstand entsteht, hat weder Android SDK noch Zugang zu Maven Central und Google Maven. Geprüft wurde stattdessen, was sich ohne Werkzeugkette prüfen lässt — die Ableitungsregeln und der Regeltext-Zerleger gegen die ausgelieferte `catalog.db`, spaltenweise und über alle 62 Karten. Das belegt die Regeln, nicht den Kotlin-Code. Der Lauf gehört zur Abnahme und ist keine Formalie.

**Bewusst weggelassen:** ein Konventions-Plugin für die wiederholten Android-Blöcke in den sieben Bibliotheksmodulen. Solange die Module leer sind, wäre `buildSrc` mehr Infrastruktur als Nutzen und ein zusätzliches Risiko beim ersten Build. Der richtige Zeitpunkt ist, wenn die Module echten Inhalt bekommen — vermerkt in `Modules.md`.
