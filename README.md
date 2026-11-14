# Rennrad-Tracker

Android zeichnet manuell gestartete Fahrten auf. Der feste Familienlink öffnet eine mobile Live-Karte im Browser und zeigt pro Handy die aktuelle oder zuletzt beendete Fahrt. In der Kartenübersicht sind alle Strecken gemeinsam sichtbar. Die Geräteauswahl öffnet die jeweiligen Fahrtdaten. Der Link bleibt gültig, bis er in der App erneuert wird.

Auf der Startseite lässt sich der vollständige Familienlink oder seine Kennung nach `/f/` eingeben. Die Webseite bietet Deutsch und Englisch. Die Android-App folgt der Gerätesprache oder der unter Android eingestellten App-Sprache.

Die vier Fragmente in `server/web/legal/` stammen aus BLEBridge (`apps/web/src/content`, Stand `d595ffb`). Das Impressum ist bytegleich kopiert. Für die Datenschutzerklärung wurde die unveränderte JSX-Vorlage mit React zu statischem HTML gerendert. Der Wortlaut wird beim Ausliefern direkt übernommen.

## Weiteres Handy koppeln

1. Auf dem neuen Handy die App installieren und **Handy per QR-Code koppeln** wählen. Gerätenamen eingeben und den QR-Code anzeigen.
2. Auf einem eingerichteten Handy unter **Handys** auf **QR-Code scannen** tippen, den Code erfassen und **Handy hinzufügen** bestätigen.
3. Sobald das neue Handy **Handy gekoppelt** meldet, ist es einsatzbereit. Beide Handys können gleichzeitig tracken. Über **Verbundene Handys** lässt sich ein anderes Handy einzeln entfernen.

Ein QR-Code gilt fünf Minuten. Er enthält eine Kopplungsanfrage. Der Geräteschlüssel wird auf dem neuen Handy erzeugt. Die veröffentlichte App ist für `https://biketracking.tobiga.com` vorkonfiguriert.

## Lokal ausprobieren

Rust installieren und in `server/` ausführen. PowerShell erzeugt hier einen zufälligen Schlüssel für den Serverbetrieb.

```powershell
$env:TRACKER_OWNER_KEY = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
cargo run --locked
```

Die Startseite ist unter `http://127.0.0.1:8787` erreichbar. Verwende für den Standard-Android-Emulator beim Debug-Build `-PtrackerServerUrl=http://10.0.2.2:8787`. Auf einem per USB angeschlossenen Gerät kannst du den Port mit `adb reverse tcp:8787 tcp:8787` weiterleiten und `-PtrackerServerUrl=http://127.0.0.1:8787` verwenden.

Für den Android-Build brauchst du JDK 17 oder 21 und das Android SDK 36. Öffne `android/` in Android Studio oder setze `ANDROID_HOME` und führe in diesem Ordner `gradlew.bat :app:assembleDebug` unter Windows bzw. `./gradlew :app:assembleDebug` unter Linux oder macOS aus. Die installierbare Debug-APK liegt unter `android/app/build/outputs/apk/debug/app-debug.apk`. Die App unterstützt Android 8 und höher.

Für die Strecke braucht die App den genauen Standort. Über die Android-Benachrichtigung lässt sich eine laufende Fahrt auch außerhalb der App beenden.

## Web-E2E-Tests

Mit Node.js und Rust im Projektordner ausführen. Unter Windows bei Bedarf `npm.cmd` und `npx.cmd` verwenden.

```sh
npm ci
npx playwright install chromium --only-shell
npm run test:e2e
```

Die Tests bauen den lokalen Rust-Dienst und starten ihn jeweils mit eigenen Testdaten. In einem mobilen Chromium-Viewport prüfen sie zwei gleichzeitig sichtbare Fahrten, ihre separat aktualisierten Fahrtdaten und das Ende einer Fahrt, während die zweite live bleibt. Die Kartenkacheln werden lokal ersetzt.

## Für die Familie erreichbar machen

Die Instanz unter `https://biketracking.tobiga.com` nutzt Compose in `/opt/rennrad-tracker` auf `213.133.99.169`. Traefik übernimmt HTTPS. Der Container speichert die Fahrten und den Familienlink im Docker-Volume `rennrad-tracker_rides`.

### Erstes Handy einrichten oder Zugang wiederherstellen

Wenn kein eingerichtetes Handy verfügbar ist, zeigt die App wie gewohnt einen Kopplungs-QR-Code. Lies ihn mit einem QR-Decoder als Text aus. Die Person mit Serverzugriff kann die Anfrage unter `/api/owner/pair` mit `TRACKER_OWNER_KEY` bestätigen. Der folgende PowerShell-Befehl liest den QR-Text aus der Zwischenablage und bestätigt ihn für die Live-Instanz:

```powershell
$qr = (Get-Clipboard -Raw).Trim()
$prefix = 'rennrad-pair:1:'
if (!$qr.StartsWith($prefix)) { throw 'Ungültiger Kopplungscode' }
$request = $qr.Substring($prefix.Length)
if (($request | ConvertFrom-Json).base -ne 'https://biketracking.tobiga.com') { throw 'Falsche Tracker-Installation' }
$ownerKey = (ssh 213.133.99.169 'cut -d= -f2- /opt/rennrad-tracker/tracker.env').Trim()
try {
    Invoke-RestMethod 'https://biketracking.tobiga.com/api/owner/pair' -Method Post -ContentType 'application/json' `
        -Headers @{ Authorization = "Bearer $ownerKey" } -Body $request
} finally {
    Remove-Variable ownerKey
}
```

### Eigener Server

Starte den Rust-Dienst auf deinem Server und leite eine HTTPS-Domain an den Dienst weiter. Beispiel für einen Caddy-Reverse-Proxy auf demselben Host:

```caddyfile
rad.example.org {
    reverse_proxy 127.0.0.1:8787
}
```

Setze `TRACKER_OWNER_KEY` auf einen eigenen zufälligen Schlüssel und `TRACKER_DATA` auf einen dauerhaften, privaten Ordner. `TRACKER_BIND` ist standardmäßig `127.0.0.1:8787`. Setze die HTTPS-Domain beim Android-Build mit `-PtrackerServerUrl=https://rad.example.org`. Zugriff auf die Karte hat jede Person mit dem Familienlink. Vermeide es, URLs mit diesem Link in Proxy-Zugriffsprotokollen zu speichern. Die Basiskarte lädt ihre Kacheln direkt von OpenStreetMap und zeigt deren Namensnennung an. Siehe [Kachelnutzungsrichtlinie](https://operations.osmfoundation.org/policies/tiles/).

`family.json` enthält den Link. `devices.json` enthält die Gerätenamen und Prüfsummen der Geräteschlüssel. Je Fahrt gibt es eine NDJSON-Datei mit einer Zeile pro bestätigtem Upload. Sichere den Datenordner, um Link, Geräte und Fahrten zu behalten. Die Fahrdateien bleiben gespeichert, bis sie im Datenordner gelöscht werden. Änderungen an den Dateien bei gestopptem Dienst vornehmen.
