# Rise 1.0

Android-App, die eine Partie **MTG Treachery** am Tisch begleitet: Sie verteilt die verdeckten Identitäten digital, führt den Spielverlauf mit und wertet nach der Partie aus. Jeder Spieler nutzt sein eigenes Gerät.

**Die vollständige Dokumentation liegt nicht in diesem Repository, sondern im Obsidian-Vault** unter `Rise1/`. Einstieg: `Rise1/00_Project/README.md`. Architektur: `Rise1/01_Architecture/TDD v1.0.md`.

Dieses README beschreibt nur, wie man das Projekt baut.

---

## Stand

**Gerüst — T-001 bis T-005**, dazu der Katalog aus `T-010` bis `T-017`. Es gibt noch kein Spiel. Die neun Module aus TDD 2.2 stehen, die Modulgrenzen werden automatisch geprüft, und die Protokollierung verweigert geheime Werte.

Die APK startet mit dem **Katalog-Browser**: 62 Identitäten, Filter nach Rolle und Marke, Suche, Einzelansicht mit Regeltext und Rulings. Die Statusseite aus `T-005` ist über die Kopfzeile erreichbar und wird mit `T-140` durch die Tischansicht ersetzt.

Die 62 Kartenbilder liegen unter `catalog/src/main/assets/cards` (6,62 MiB). Fehlen sie, zeigt der Browser Anfangsbuchstaben und weist einmal oben in der Liste darauf hin; Bezug mit `./gradlew images` im Import-Werkzeug.

## Voraussetzungen

JDK 17 oder neuer · Android SDK mit Platform **API 36** · Android Studio empfohlen (legt `local.properties` selbst an)

## Erster Build

```
./gradlew checkAll
./gradlew :ui:assembleDebug
```

Die APK liegt danach unter `ui/build/outputs/apk/debug/ui-debug.apk`.

### Wenn der erste Build scheitert

Die Versionen in `gradle/libs.versions.toml` konnten in der Umgebung, in der dieses Projekt entstanden ist, **nicht gegen die Repositories geprüft werden** — es gab dort keinen Netzzugang zu Maven Central und Google Maven. Erwartbar sind daher genau zwei Arten von Fehlern:

**Eine Version existiert nicht.** Gradle nennt das fehlende Artefakt. Betroffen sind `agp`, `kotlin` und `tink` sowie die vier aus `T-014` — `ksp`, `room` und die drei `androidx-test-*` — in `gradle/libs.versions.toml`. Aktuelle Stände stehen bei den jeweiligen Projekten; die Datei ist die einzige Stelle, an der etwas zu ändern ist, und sie nennt zu jeder Koordinate den Rückfall.

**KSP passt nicht zu Kotlin.** Das Präfix der KSP-Version **ist** die Kotlin-Version. Zu `kotlin = "2.2.20"` passt nur eine `2.2.20-*`; eine für 2.2.21 bricht mit `… is too old for kotlin-2.2.20`. Kotlin wird dafür **nicht** angehoben — das zöge Compose und AGP mit.

**Das Android-Gradle-Plugin kennt API 36 nicht.** Dann ist `agp` zu alt. `compileSdk` und `targetSdk` sind aus `D-001 SDK-Level` festgelegt und werden **nicht** gesenkt — stattdessen steigt die AGP-Version.

Beides ist Konfiguration, keine Architekturfrage. Sollte sich zeigen, dass die Vorgaben aus D-001 nicht erfüllbar sind, gilt das Verfahren aus dem Vault-README: Task stoppen, Befund melden, nicht umgehen.

## Was `checkAll` prüft

`verifyModuleBoundaries` (T-003) prüft die Modulgrenzen aus TDD 2.2 — vor allem, dass `:host` keinen Zugriff auf `:crypto` oder `:deal` hat. Das ist die zentrale Grenze des Sicherheitsmodells und der Grund, warum diese Prüfung in jedem Lauf mitläuft, statt eine Abnahme am Ende zu sein.

Danach laufen die Unit-Tests aller Module.

## Modulschnitt

| Modul | Art | Aufgabe | Inhalt kommt in |
|---|---|---|---|
| `core` | Kotlin/JVM | Domänenmodell, Protokollierung, Fehler | E04 |
| `projection` | Kotlin/JVM | Faltet Events zum Anzeigezustand | E04 |
| `catalog` | Android-Bibliothek | Kartendaten aus `catalog.db`, Regeltext-Zerleger | **E02, umgesetzt** |
| `crypto` | Android-Bibliothek | Schlüssel, Ver- und Entschlüsselung | E05 |
| `deal` | Android-Bibliothek | Zwei-Parteien-Verfahren | E09 |
| `transport` | Android-Bibliothek | WLAN, TLS, WebSocket | E06 |
| `session` | Android-Bibliothek | Beitritt, Heartbeat, Wiedereinstieg | E08 |
| `host` | Android-Bibliothek | Reihenfolge, Log, Zustellung | E07 |
| `ui` | Android-Anwendung | Darstellung, deutsche Ressourcen | **Katalog-Browser seit T-017**, Rest E10 |

`core` und `projection` sind absichtlich reine JVM-Module: Das Event-Modell und die Projektion sind der am dichtesten getestete Teil des Systems, und JVM-Tests brauchen keinen Emulator.

## Werkzeuge unter `tools/` — nicht Teil des App-Builds

`tools/catalog-import` ist ein **eigenständiger Gradle-Build** mit eigener `settings.gradle.kts`. Er steht in keinem `include` der Projektwurzel, erscheint deshalb nicht in `subprojects` und lässt `verifyModuleBoundaries` unberührt. Die Regel „genau neun Module" aus TDD 2.2 bleibt damit gültig, ohne dass ein Build-Werkzeug sie verletzt.

`./gradlew checkAll` baut und prüft dieses Werkzeug **nicht**. Es wird ausdrücklich aufgerufen, mit einem **eigenen Wrapper** auf derselben Gradle-Version 8.14.3 wie die Projektwurzel:

```
cd tools/catalog-import
./gradlew build       # kompiliert und führt die 89 Tests aus
./gradlew validate    # prüft Prüfsumme und Struktur der Quelldatei
./gradlew images      # bezieht die 62 Kartenbilder nach assets/cards
./gradlew database    # baut catalog.db, reproduzierbar geprüft
./gradlew keywords    # leitet identity_keyword aus dem Regeltext ab
./gradlew roomStamp   # trägt Rooms identityHash und user_version nach
```

Zwei weitere Befehle haben bewusst **keine** eigene Aufgabe und laufen über `run`:

```
./gradlew run --args="transform"   # Abbildungsbericht, erzeugt keine Datei
./gradlew run --args="checksum"    # schreibt die Prüfsumme neu, wenn die Quelle bewusst erneuert wurde
```

**Die Aufrufform dahinter.** Jeder Befehl von `Main.kt` nimmt genau **ein** Argument: das Verzeichnis mit der Quelldatei, voreingestellt `catalog-source`. Die Ziele leitet das Werkzeug daraus ab — es nimmt das übergeordnete Verzeichnis als Repository-Wurzel und hängt `catalog/src/main/assets/cards` beziehungsweise `catalog/src/main/assets/catalog.db` an. Deshalb übergeben alle Aufgaben denselben Pfad.

Der eigene Wrapper ist Absicht: Ein eigenständiger Build ohne Wrapper würde mit dem gebaut, was zufällig installiert ist. Die Version gehört auch hier ins Repository.

### `catalog.db` neu bauen — die Reihenfolge zählt

Room prüft beim Öffnen einer mitgelieferten Datenbank ihren Schema-Hash **und** ihre `user_version`. Beides kennt erst der Compiler, deshalb entsteht die ausgelieferte Datei in vier Schritten, und die Reihenfolge ist nicht beliebig:

```
cd tools/catalog-import && ./gradlew database   # frische, leere catalog.db
./gradlew keywords                              # füllt identity_keyword aus text_raw
cd ../.. && ./gradlew :catalog:assembleDebug    # KSP schreibt catalog/schemas/…/1.json
cd tools/catalog-import && ./gradlew roomStamp  # trägt Hash und user_version nach
```

`keywords` läuft **vor** dem Übersetzen: Es legt eine Tabelle an, und ein geändertes Schema ist ein anderer `identityHash`.

`roomStamp` stempelt **nur auf einen ungestempelten Stand**. Ein zweiter Stempel auf dieselbe Datei ergibt andere Bytes als ein erster auf eine frisch gebaute — der Inhalt hinge dann an der Vorgeschichte der Datei statt an ihren Daten. Wer die Entities ändert, fährt die vier Schritte von vorn; ein erneuter Aufruf mit unverändertem Hash lässt die Datei unangetastet und sagt das.

Der Schemaexport unter `catalog/schemas/` ist **versioniert** und gehört ins Repository: Er ist die Schnittstelle zwischen App-Build und Werkzeug, und die beiden Builds kennen einander nicht.

### Der Abnahmetest für den Katalog braucht ein Gerät

```
./gradlew :catalog:connectedDebugAndroidTest
```

27 Tests, die das **ausgelieferte** Paket öffnen — die `catalog.db` aus dem Asset und die Bilder aus `assets/cards`:

- `CatalogAssetTest` (21) — die laufende Prüfung: Schema, 62 Identitäten, 13/18/18/13, 295 Rulings, Schlüsselwortmenge, Regeltext-Zerleger über den ganzen Bestand.
- `M2AbnahmeTest` (6) — die Abnahme für M2: 62 Karten, 13/18/18/13, ein Bild je Karte. Einzeln aufrufbar:

```
./gradlew :catalog:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=de.myhornets.rise1.catalog.M2AbnahmeTest
```

`checkAll` fährt sie nicht mit — `check` führt keine instrumentierten Tests aus.

Die 13 Tests des Regeltext-Zerlegers laufen dagegen **in** `checkAll`: Sie brauchen weder Android noch die Datenbank.

Die Quelldatei `catalog-source/treachery-cards.json` liegt **versioniert im Repository** und wird nicht beim Bauen geladen. Vorgehen für ein erstmaliges oder erneutes Laden: `catalog-source/README.md`.

## Lizenz und Herkunft der Kartendaten

Die Treachery-Identitäten sind Fan-Content von mtgtreachery.net. Magic: The Gathering ist Eigentum von Wizards of the Coast; dieses Projekt steht in keiner Verbindung dazu. Attribution wird mit `T-177` in der App ergänzt.
