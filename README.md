# Rise 1.0

Android-App, die eine Partie **MTG Treachery** am Tisch begleitet: Sie verteilt die verdeckten Identitäten digital, führt den Spielverlauf mit und wertet nach der Partie aus. Jeder Spieler nutzt sein eigenes Gerät.

**Die vollständige Dokumentation liegt nicht in diesem Repository, sondern im Obsidian-Vault** unter `Rise1/`. Einstieg: `Rise1/00_Project/README.md`. Architektur: `Rise1/01_Architecture/TDD v1.0.md`.

Dieses README beschreibt nur, wie man das Projekt baut.

---

## Stand

**Gerüst — T-001 bis T-005**, dazu das Import-Werkzeug aus `T-010`. Es gibt noch kein Spiel. Die neun Module aus TDD 2.2 stehen, die Modulgrenzen werden automatisch geprüft, und die Protokollierung verweigert geheime Werte. Alles Weitere ist leer.

Die APK startet und zeigt eine Statusseite. Das ist ein Platzhalter und wird mit `T-140` ersetzt.

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

**Eine Version existiert nicht.** Gradle nennt das fehlende Artefakt. Betroffen sind `agp`, `kotlin` und `tink` in `gradle/libs.versions.toml`. Aktuelle Stände stehen bei den jeweiligen Projekten; die Datei ist die einzige Stelle, an der etwas zu ändern ist.

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
| `catalog` | Android-Bibliothek | Kartendaten aus `catalog.db` | E02 |
| `crypto` | Android-Bibliothek | Schlüssel, Ver- und Entschlüsselung | E05 |
| `deal` | Android-Bibliothek | Zwei-Parteien-Verfahren | E09 |
| `transport` | Android-Bibliothek | WLAN, TLS, WebSocket | E06 |
| `session` | Android-Bibliothek | Beitritt, Heartbeat, Wiedereinstieg | E08 |
| `host` | Android-Bibliothek | Reihenfolge, Log, Zustellung | E07 |
| `ui` | Android-Anwendung | Darstellung, deutsche Ressourcen | E10 |

`core` und `projection` sind absichtlich reine JVM-Module: Das Event-Modell und die Projektion sind der am dichtesten getestete Teil des Systems, und JVM-Tests brauchen keinen Emulator.

## Werkzeuge unter `tools/` — nicht Teil des App-Builds

`tools/catalog-import` ist ein **eigenständiger Gradle-Build** mit eigener `settings.gradle.kts`. Er steht in keinem `include` der Projektwurzel, erscheint deshalb nicht in `subprojects` und lässt `verifyModuleBoundaries` unberührt. Die Regel „genau neun Module" aus TDD 2.2 bleibt damit gültig, ohne dass ein Build-Werkzeug sie verletzt.

`./gradlew checkAll` baut und prüft dieses Werkzeug **nicht**. Es wird ausdrücklich aufgerufen, mit einem **eigenen Wrapper** auf derselben Gradle-Version 8.14.3 wie die Projektwurzel:

```
cd tools/catalog-import
./gradlew build       # kompiliert und führt die 17 Tests aus
./gradlew validate    # prüft Prüfsumme und Struktur der Quelldatei
```

Der eigene Wrapper ist Absicht: Ein eigenständiger Build ohne Wrapper würde mit dem gebaut, was zufällig installiert ist. Die Version gehört auch hier ins Repository.

Die Quelldatei `catalog-source/treachery-cards.json` liegt **versioniert im Repository** und wird nicht beim Bauen geladen. Vorgehen für ein erstmaliges oder erneutes Laden: `catalog-source/README.md`.

## Lizenz und Herkunft der Kartendaten

Die Treachery-Identitäten sind Fan-Content von mtgtreachery.net. Magic: The Gathering ist Eigentum von Wizards of the Coast; dieses Projekt steht in keiner Verbindung dazu. Attribution wird mit `T-177` in der App ergänzt.
