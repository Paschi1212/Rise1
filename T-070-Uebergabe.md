# Übergabe an die lokale Sitzung — T-070 sofort implementieren

Diese Nachricht als **erste Eingabe** in die neu auf dem PC gestartete Cowork-Aufgabe einfügen.
Projektwurzel: `C:\Users\Paschi\Rise1` · Vault: `C:\Users\Paschi\Obsidian\Rise1`

---

## Status — nicht erneut prüfen

Der vorherige Cloud-Lauf ist an einem Werkzeug-Blocker gescheitert, nicht fachlich: die Cloud-Dateibrücke
liest keine `.kt`-Dateien. Deshalb der Wechsel auf den PC. Fachlich ist nichts offen.

Auf dem PC bereits verifiziert — **nicht erneut untersuchen, nicht ändern**:

AGP 8.13.0 · Gradle 8.14.3 · Kotlin 2.2.20 · KSP 2.2.20-2.0.4 · Room 2.8.4 ·
compileSdk 36 · targetSdk 36 · minSdk 29 · JDK 21 · `checkAll` zuletzt grün · 402 JVM-Testmethoden · E08 abgeschlossen.

Die zwischenzeitliche AGP-9/SDK-37-Toolchain ist vollständig zurückgesetzt. Nicht anfassen.

**Nicht erneut:** AGP/Gradle/SDK/Kotlin/KSP prüfen · ADRs neu erfinden · Toolchain ändern.

**Aufräumen:** In `Rise1\build\` liegt eine Testdatei `_bridge_probe.kt` aus dem Brücken-Test. Löschen.
(`build/` ist gitignoriert, `./gradlew clean` räumt sie ohnehin weg.)

---

## Auftrag

**T-070 „TLS mit Partie-Zertifikat"** aus [[E06 Transport]] implementieren: der echte, socketbasierte
Transport neben dem `AttrappenTransport`, im Modul `:transport`.

## Verbindliche Vorgaben — bereits gelesen und ausgewertet

Maßgeblich sind **ADR-001 (inkl. Ergänzungen 10 und 11), ADR-006, ADR-007, ADR-008**.
Es gibt **keine offene Architekturfrage** vor T-070. Die für T-070 bindenden Punkte, verdichtet:

### Aus ADR-001 + Ergänzung 10 (Rahmung ohne WebSocket)
- Transportweg: lokales WLAN, **TCP über TLS**, `NsdManager`, selbstsigniertes **partiegebundenes** Zertifikat
  mit Fingerabdruckprüfung.
- **Kein WebSocket.** Innerhalb der TLS-Verbindung gilt die eigene Rahmung aus `T-072`:
  `['R']['1'][Version][Typ][Länge 4 Byte BE][Nutzlast]`.
- Der Herzschlag trägt einen `HERZSCHLAG`-Rahmentyp, keinen WebSocket-Ping.
- **Keine neue Abhängigkeit** in `:transport` — das Modul hat heute keine.
- TLS ersetzt die Ende-zu-Ende-Verschlüsselung aus TDD 7 nicht.

### Aus ADR-006 (Host-Zertifikat)
- Zertifikat entsteht über `KeyGenParameterSpec` im `AndroidKeyStore`; Alias trägt die `match_uid`.
- `KeyStore.PrivateKeyEntry` → `KeyManagerFactory` → `SSLContext`.
- Client prüft **den Fingerabdruck**, nicht die Kette. `FingerabdruckPruefer` ist der **einzige** TrustManager.
- **Kein TrustManager, der alles annimmt — auch nicht vorübergehend, auch nicht in Tests.**
  Keine Test-Hintertür, durch die die Fingerprint-Prüfung umgangen werden kann.
- `setIsStrongBoxBacked` wird **nicht** gesetzt.

### Aus ADR-008 (Nebenläufigkeit) — das Threadmodell für T-070
| Thread | Anzahl | Aufgabe |
|---|---|---|
| Annahme | 1 (nur Host) | `accept()` auf dem `SSLServerSocket`, richtet Verbindungen ein |
| Lesen | 1 je Verbindung | blockierendes `read`, füttert den `Rahmenleser`, reicht fertige Rahmen weiter |
| Senden | 1 je Verbindung | nimmt aus einer `BlockingQueue`; kein Aufrufer blockiert je auf dem Socket |
| Sitzung | **1 im Prozess** | führt **alle** Rückrufe aus |

- Regel: **Alles oberhalb von `Transport` läuft auf dem Sitzungsthread — und nur dort.** Keine bestehende
  Klasse wird threadsicher gemacht; ein `synchronized` in `Verbindungsautomat` wäre ein Fehlerzeichen.
- Einziger nebenläufiger Übergabepunkt: Lesethread → Warteschlange → Sitzungsthread.
- Obergrenze hart: `2 × Sitzplatzzahl + 1`. Mehr Verbindungen als Sitzplätze werden **abgelehnt**, nicht geparkt.
- Beenden: Socket schließen, beide Threads beenden, **genau ein** `Getrennt` auf dem Sitzungsthread —
  über ein `AtomicBoolean` je Verbindung. Nach Herunterfahren ist der Transport **unbrauchbar**
  (gleiche Regel wie `Rahmenleser` nach Protokollfehler).
- Verspätete Rahmen: jede Verbindung trägt eine **laufende Nummer**; Rahmen aus geschlossenen Verbindungen
  werden **verworfen**.
- **Keine Coroutinen** (das wäre eine neue Abhängigkeit) — `ExecutorService` mit einem Thread.
  **Kein NIO**, blockierendes I/O.
- **Keine Änderung am `Transport`-Vertrag aus T-065.** Der `AttrappenTransport` bleibt einfädig und
  deterministisch und bleibt daneben stehen.

### Aus ADR-007 (Nutzlastformat)
- Eigenes längenpräfigiertes Binärformat, Fassungsbyte, kein JSON, keine Abhängigkeit.
- Jede Länge wird gegen den Rest geprüft; überzählige Bytes am Ende werden abgelehnt.
- Ein Leser mit Protokollfehler gibt **nichts** teilweise heraus.
- `rejoin_token` darf in **keiner** Fehlermeldung und in keinem `toString` auftauchen.

## Testabgrenzung (aus ADR-008, „Was ein JVM-Test hier prüfen kann")

- **JVM-Tests** prüfen die Socket-Mechanik über **Klartext-Loopback-Sockets**: die Übergabestelle,
  das Beenden, die laufende Nummer gegen verspätete Rahmen.
- **TLS/Fingerabdruck-Integration ist ein Gerätetest** und wird als solcher gekennzeichnet — nicht mit
  einem abgeschwächten TrustManager scheinbar prüfbar gemacht.

## Bestehender Stand in `:transport`

`Transport.kt` (T-065) · `AttrappenTransport.kt` (T-066) · `Hostattrappe.kt` + `Attrappennetz.kt` (T-067) ·
`Dienstverzeichnis.kt` + `NsdDienstverzeichnis.kt` + `AttrappenVerzeichnis.kt` (T-068/T-069) ·
`Rahmen.kt` (T-072) · Tests: `AttrappenTransportTest.kt`, `AttrappennetzTest.kt`,
`DienstverzeichnisTest.kt`, `RahmenTest.kt`.

Die neue Implementierung kommt **neben** `AttrappenTransport` in `:transport`.
`Transport` (T-065) bleibt Wort für Wort unverändert.

## Vorgehen

Erst `Transport.kt`, `Rahmen.kt` und `AttrappenTransport.kt` lesen, dann implementieren, dann
`./gradlew checkAll` laufen lassen.

Falls während der Implementierung **tatsächlich** ein Widerspruch zu ADR-001/006/007/008 auftaucht:
**stoppen** und nur diesen konkreten Widerspruch melden. Ansonsten: Code schreiben.
