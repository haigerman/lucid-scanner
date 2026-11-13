# Lucid Scanner

Android-App, die Papier Blatt für Blatt mit der Handykamera scannt, daraus ein PDF macht und
es direkt in deiner **OpenCloud** ablegt – in einem deiner Favoriten-Ordner oder einem beliebigen anderen.

## So geht ein Scan

1. **SW-Scan** oder **Farbscan** antippen – die Kamera öffnet sich.
2. Blatt ins Bild legen. Die App erkennt das helle Blatt, zeichnet seinen Umriss und löst
   von selbst aus, sobald Handy und Blatt kurz still sind (der Ring um den Auslöser füllt sich).
3. Blatt wechseln. Sobald das nächste Blatt ruhig im Bild liegt, wird Seite 2 erfasst – und so weiter.
4. **Fertig** antippen. Aus allen Seiten entsteht ein PDF.
5. **Kontrolle:** Das PDF wird angezeigt, der Dateiname lässt sich noch ändern. Dann
   **Verwerfen** oder **In OpenCloud ablegen**.
6. **Wohin?** Die App fragt nach dem Zielordner: einer der **Favoriten** – oder
   **Anderer Ordner …**. Der öffnet den Ordnerbrowser: durch die Ordner der OpenCloud blättern,
   bei Bedarf mit **＋ Neuer Ordner** einen anlegen, dann **Hier ablegen**.

Gut zu wissen:

- Dieselbe Seite wird nicht doppelt erfasst: Nach einer Aufnahme wartet die App, bis das Blatt
  kurz aus dem Bild war oder sich sein Inhalt sichtbar geändert hat.
- **SW-Scan** macht Graustufen-Seiten mit kräftigem Kontrast (weißes Papier, satt schwarze
  Schrift) – ideal für Briefe und Formulare. **Farbscan** behält die Farben.
- Der Auslöser lässt sich auch **von Hand** antippen – etwa wenn das Blatt nicht erkannt wird
  (weißes Papier auf weißem Tisch). Danach kommt der **Zuschnitt**: Das Foto wird gezeigt, die
  vier Ecken lassen sich auf die Ecken des Blatts ziehen (vorbelegt mit dem erkannten Blatt,
  sonst mit einem Rahmen). **Übernehmen** macht daraus die Seite, und es geht direkt mit der
  nächsten Seite weiter; **Verwerfen** wirft das Foto weg.
- **↶** neben dem Vorschaubild nimmt die letzte Seite wieder heraus, **🔦** schaltet das Licht ein.
- Jede Seite wird entzerrt (auch schräg fotografiert kommt ein A4-Blatt als A4-Seite heraus) und
  aufgehellt: Schatten werden ausgeglichen, das Papier wird weiß. Für Farbscans lässt sich das
  Aufhellen in den Einstellungen abschalten, z. B. für Fotos.
- Am besten liegt das Blatt auf einem **dunkleren Untergrund** und ist ganz im Bild.

## Einrichtung (einmalig)

In der App unter **⚙ Einstellungen**:

| Feld | Inhalt |
|---|---|
| **Adresse** | Adresse deiner OpenCloud, z. B. `cloud.example.com` |
| **Benutzername** | dein OpenCloud-Benutzername |
| **App-Token** | in OpenCloud unter *Einstellungen → App-Tokens → Neu* erzeugen und hier einfügen |

**Verbindung prüfen** zeigt sofort, ob Adresse und Anmeldung stimmen.

Darunter stehen die **Ordner-Favoriten**: Ordner im persönlichen Bereich, die beim Ablegen zur
Auswahl stehen. **＋ Ordner wählen** öffnet den Ordnerbrowser (dort lässt sich auch ein neuer
Ordner anlegen), **Pfad eingeben** nimmt einen Pfad wie `Dokumente/Scans` direkt an – fehlt der
Ordner, wird er beim ersten Ablegen angelegt. **✕** entfernt einen Favoriten wieder.

OpenCloud meldet App-Tokens zusammen mit dem Benutzernamen an – deshalb braucht die App beides.
Der Token wird auf dem Gerät verschlüsselt gespeichert (Schlüssel im Android-Keystore) und ist
von Backups ausgenommen.

Die App findet den persönlichen Bereich selbst. Soll stattdessen in einen **anderen Space**
abgelegt werden, als Adresse dessen WebDAV-Adresse eintragen
(`https://cloud.example.com/dav/spaces/<Space-ID>`; OpenCloud zeigt sie in den Details des Space,
wenn in den Kontoeinstellungen „WebDAV-Informationen anzeigen“ aktiv ist).

Vorhandene Dateien werden nie überschrieben: Gibt es den Namen schon, meldet die App das, und
der Scan bleibt in der Kontrollansicht.

## Aufbau

| Datei | Aufgabe |
|---|---|
| `MainActivity.kt` | Startet die Oberfläche, wechselt zwischen den vier Bildschirmen |
| `ui/HomeScreen.kt` | Startbildschirm: „SW-Scan“ und „Farbscan“, zuletzt abgelegte Scans |
| `ui/CameraScreen.kt` | Kamera mit Blattumriss, Auslöser, „Fertig“ |
| `ui/CropEditor.kt` | Zuschnitt von Hand nach einer selbst ausgelösten Aufnahme |
| `ui/ReviewScreen.kt`, `ui/PdfPreview.kt` | Kontrollansicht: zeichnet die Seiten aus der fertigen PDF-Datei |
| `ui/SettingsScreen.kt` | OpenCloud-Zugang, Ordner-Favoriten, Aufhellen |
| `ui/FolderBrowser.kt` | Ordnerbrowser: blättern, neuen Ordner anlegen, auswählen |
| `scan/ScanCamera.kt` | CameraX: Vorschau, laufende Analyse, Foto |
| `scan/DocumentDetector.kt` | Findet das Blatt: Schwelle nach Otsu → größte helle Fläche → Viereck mit angepassten Kanten |
| `scan/CaptureTrigger.kt` | Entscheidet, wann ausgelöst wird (Haltezeit, keine Doppelaufnahmen) |
| `scan/PageProcessor.kt`, `scan/Perspective.kt` | Foto → Seite: Blatt im Foto finden, entzerren, wahres Seitenverhältnis berechnen |
| `scan/PageEnhancer.kt` | Schattenausgleich und Weißabgleich des Papiers, Graustufen für SW-Scans |
| `pdf/PdfWriter.kt` | Schreibt das PDF; die JPEG-Seiten werden unverändert eingebettet |
| `cloud/OpenCloudClient.kt` | WebDAV: persönlichen Bereich finden, Ordner auflisten und anlegen, hochladen |
| `data/Settings.kt`, `data/ScanRepository.kt` | Einstellungen und der laufende Scan |

Blatterkennung, Entzerrung und PDF sind selbst geschrieben – die App braucht weder Google-Dienste
noch OpenCV. Mindestens Android 8.0 (API 26), Ziel-API 36.

Die Werte zum Nachstellen stehen in `scan/CaptureTrigger.kt` (Haltezeit `holdMs`, erlaubte
Bewegung `moveTolerance`) und `scan/PageProcessor.kt` (Seitengröße `MAX_PAGE_SIDE`, JPEG-Qualität).

## Lokal bauen

Voraussetzungen: JDK 17+ und das Android SDK (Platform 36).

```bash
./gradlew :app:assembleDebug
```

Die APK liegt danach unter `app/build/outputs/apk/debug/app-debug.apk`.
Tests: `./gradlew :app:testDebugUnitTest` – geprüft werden u. a. die Blatterkennung an
künstlichen Kamerabildern, der Weg vom Foto zur fertigen Seite und der Upload gegen einen
nachgebauten OpenCloud-Server.

## Release (Installation über Obtainium)

1. Einmalig Repository-Secrets anlegen (*Settings → Secrets and variables → Actions*):
   - `KEYSTORE_BASE64` – Keystore, base64-kodiert (`base64 -w0 release.jks`)
   - `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

   Alternativ funktionieren auch die Namen `SIGNING_KEYSTORE_BASE64`,
   `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.

   Den Keystore gut aufbewahren – Updates funktionieren nur mit derselben Signatur.
2. Tag pushen:
   ```bash
   git tag v0.1.0 && git push origin v0.1.0
   ```
   Der Workflow baut die signierte APK und hängt sie an ein GitHub-Release.
3. In Obtainium: *App hinzufügen* → `https://github.com/haigerman/lucid-scanner`.

Versionscode wird aus dem Tag berechnet (`v1.2.3` → 10203).
