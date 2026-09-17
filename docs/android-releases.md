# Signierte Android-Updates

KontoKlar prüft auf ein öffentliches GitHub-Release, lädt die APK in einen privaten App-Cache, verifiziert SHA-256, Paketname, höhere Versionsnummer und das installierte Signaturzertifikat und übergibt sie dann an den Android-Paketinstaller. Android zeigt die Bestätigung; die App installiert nicht still im Hintergrund.

## Release-Signaturschlüssel

Die ersten lokalen Testinstallationen verwendeten den standardmäßigen Android-Debug-Keystore. Die veröffentlichten Releases verwenden stattdessen einen privaten, dedizierten Release-Schlüssel. Die veröffentlichten Updates `v0.39.0` und `v0.40.0` verwenden denselben Schlüssel; sein Zertifikatsfingerprint stimmt mit dem unten aufgeführten Fingerprint überein. Der private Schlüssel darf weder neu erzeugt noch verloren oder in Git eingecheckt werden.

Ein APK, das noch mit dem öffentlichen Debug-Schlüssel installiert wurde, kann Android nicht direkt durch ein mit dem privaten Release-Schlüssel signiertes APK aktualisieren. Vor dem Wechsel einer solchen lokalen Testinstallation bitte in der App ein verschlüsseltes Backup erstellen; die Release-App muss dann frisch installiert und das Backup wiederhergestellt werden. Updates zwischen den mit dem stabilen Release-Schlüssel signierten Releases funktionieren normal.

## Geheimnisse und Ablage

Der private PKCS#12-Keystore liegt lokal unter `signing/kontoklar-release.p12` auf der externen SSD; der Ordner ist in `.gitignore` ausgeschlossen. Das starke Keystore-Passwort liegt im macOS-Schlüsselbund und als GitHub Actions Secret; nie ins Repository, in die App oder in Chat kopieren. Die drei benötigten Repository-Secrets sind vorhanden. GitHub Actions benötigt:

* `KONTOKLAR_SIGNING_KEY_BASE64` – Base64 des PKCS#12-Keystores
* `KONTOKLAR_SIGNING_PASSWORD` – Passwort, das den Keystore und seinen Schlüssel schützt
* `KONTOKLAR_SIGNING_KEY_ALIAS` – Alias des Release-Schlüssels

Aktueller Release-Zertifikat-Fingerprint (SHA-256): `5F:7E:4A:B0:E8:07:51:A5:E9:2D:CE:96:78:E7:69:AB:9E:5F:A0:89:18:E6:E2:4B:C0:1D:95:56:BF:75:67:3F`. Das ist der öffentliche Zertifikatsfingerprint, nicht das private Schlüsselmaterial.

Der Tag-Workflow testet die App, erstellt ein signiertes Release-APK und veröffentlicht es als GitHub Release. Tags müssen einmalig und aufsteigend sein; Android akzeptiert keine gleich alte oder niedrigere `versionCode`.

Die nicht geheime Repository-Variable `KONTOKLAR_BANKING_API_BASE_URL` wird in den Release-Build übernommen. Solange kein produktionsfreigegebener Open-Banking-Worker existiert, darf sie leer bleiben; Live-Banking ist dann in der App deaktiviert. Die Variable enthält ausschließlich die HTTPS-Adresse, keine finAPI-Credentials.

## Release auslösen

1. `versionCode` erhöhen und `versionName` setzen. Der aktuell veröffentlichte Stand ist `versionCode 76` / `versionName 0.76.0`; für die nächste Änderung beide Werte erhöhen.
2. `./gradlew testDebugUnitTest assembleDebug` erfolgreich ausführen.
3. Den gewünschten Quellstand committen und pushen.
4. Einen neuen Tag erstellen und pushen, zum Beispiel `v0.44.0`.
5. GitHub Actions und die Asset-Prüfsumme kontrollieren. Erst danach erscheint das Update in der App.

`assembleRelease` bricht absichtlich ab, wenn die Release-Secrets fehlen. Debug-Builds benötigen den Release-Schlüssel nicht.

## Quellarchiv

Das vollständige Quellarchiv für Drive muss neben `app/` auch `banking-api/` (Cloudflare-Worker, Migrationen und Tests) enthalten. Lokale Build-Ausgaben, `node_modules/`, `.dev.vars` und der private Ordner `signing/` gehören nicht in das Archiv.
