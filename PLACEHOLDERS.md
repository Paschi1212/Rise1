# Bekannte Platzhalter und nicht implementierte Funktionen

Stand: T-010 umgesetzt, 2026-07-30. Diese Liste ist Teil der Abnahme und wird mit jedem Task fortgeschrieben.

## Platzhalter im Code

| Ort | Was es ist | Ersetzt durch |
|---|---|---|
| `ui/.../StatusActivity.kt` | Statusseite, die den Gerüststand anzeigt. Absichtlich ohne AppCompat und ohne Compose gebaut, um Versionsrisiken beim ersten Build zu vermeiden | `T-140` Tischansicht |
| `*/Placeholder.kt` in sieben Modulen | Leere `internal object`, damit jedes Modul einen Quellbaum hat und der Modulschnitt baubar ist | jeweils der erste echte Inhalt des Moduls |
| `libs.versions.toml` → `tink-android` | Nur im Katalog geführt, von keinem Modul verwendet | `T-050` Krypto-Fassade |

## Fehlende Eingabe: die Quelldatei

`catalog-source/treachery-cards.json` und `catalog-source/treachery-cards.json.sha256` **liegen noch nicht vor**. Das ist kein Versäumnis, sondern die Festlegung aus `T-010`: Die Quelle wird nicht automatisch geladen. Der erste Download ist ein bewusster manueller Schritt und in `catalog-source/README.md` beschrieben — mit `User-Agent`, sonst antwortet die Quelle mit HTTP 403.

Solange beide Dateien fehlen, bricht `./gradlew validate` mit einer erklärenden Meldung ab. Genau so ist es gemeint: Es erzeugt lieber nichts, als aus einer unbestätigten Quelle zu arbeiten.

## Nicht implementiert

Sieben der neun Module sind leer. Was in welches gehört und ab welchem Epic, steht in der Tabelle im `README.md`.

Konkret gibt es noch **keine** Datenbank, **keine** Kryptografie, **keinen** Netzwerkverkehr, **keine** Rollenverteilung und **keinen** Spielzustand. Die APK startet, zeigt eine Statusseite und tut sonst nichts.

## Bewusst offene Entscheidungen

`D-003` (Zugzählung) ist noch nicht getroffen. Sie entscheidet über die Event-Typen `turn_started` / `turn_ended` und über das Feld `match_state.turn_number`. Sie blockiert `T-025` und `T-146`, aber nichts in T-001 bis T-005.

## Nicht geprüfte Annahmen

**Die Versionen in `gradle/libs.versions.toml`** — `agp`, `kotlin`, `tink` — konnten nicht gegen die Repositories geprüft werden. Siehe `README.md`, Abschnitt „Wenn der erste Build scheitert".

**Die Version `gson 2.11.0`** im Import-Werkzeug konnte ebenfalls nicht gegen Maven Central geprüft werden. Sie steht an genau einer Stelle: `tools/catalog-import/build.gradle.kts`.

**Das Import-Werkzeug wurde nie kompiliert.** Seine 17 Tests sind geschrieben, aber nie gelaufen. Geprüft wurde stattdessen die Logik: die vier SHA-256-Vektoren gegen eine unabhängige Implementierung und alle 13 Regelfälle gegen einen Nachbau derselben Regeln. Das belegt, dass die Regeln stimmen — nicht, dass der Kotlin-Code übersetzt. Der erste `cd tools/catalog-import && ./gradlew build` gehört zur Abnahme.

**Der Build selbst wurde nie ausgeführt.** In der Umgebung, in der dieses Projekt entstanden ist, fehlten das Android SDK und der Zugang zu Maven Central und Google Maven. Der Quellstand ist sorgfältig geschrieben, aber **nicht kompiliert**. Der erste lokale Build ist damit Teil der Abnahme und nicht bloß eine Formalie.

**Bewusst weggelassen:** ein Konventions-Plugin für die wiederholten Android-Blöcke in den sieben Bibliotheksmodulen. Solange die Module leer sind, wäre `buildSrc` mehr Infrastruktur als Nutzen und ein zusätzliches Risiko beim ersten Build. Der richtige Zeitpunkt ist, wenn die Module echten Inhalt bekommen — vermerkt in `Modules.md`.
