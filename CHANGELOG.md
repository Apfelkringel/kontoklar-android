# KontoKlar – Release Notes

Alle veröffentlichten Versionen werden signiert über GitHub Actions bereitgestellt; die App lädt Updates direkt aus dem öffentlichen GitHub-Release und verifiziert SHA-256, Paket-ID, Versionscode und Signaturzertifikat, bevor sie Android den Paketinstaller anzeigt.

## v0.83.5 – 17.09.2026

- Empfohlene Reihenfolge für kostenlose Bankimporte angepasst: offizieller Trade-Republic-Transaktions-Export bzw. Kontoauszug-PDF zuerst, pytr nur als optionaler Fallback außerhalb der App.
- Android-26-Kompatibilität für CSV-, XLSX- und MT940-Importe wiederhergestellt (vorher `InputStream.readNBytes`-Nutzung ab API 33).
- GitHub-Release-Upload robuster gemacht: ersetzt `softprops/action-gh-release` durch direkten `gh release upload --clobber`, um sporadische Temp-Dir-Fehler zu vermeiden.
- APK und signiertes Artefakt:
  - GitHub Release: https://github.com/Apfelkringel/kontoklar-android/releases/tag/v0.83.5
  - Drive-Ordner „KontoKlar Android“: https://drive.google.com/drive/folders/1FnRMk7gf9xzqqJuMFsmIQmO1bNKbRrPf
  - APK: https://github.com/Apfelkringel/kontoklar-android/releases/download/v0.83.5/app-release.apk
  - SHA-256: `aeea3d6cac5cfadfc8229c5bb95db38577a91204eed07c6da57ad68891e6ab3e`

## v0.83.4 – 17.09.2026

- Erste Veröffentlichung mit aktualisiertem Bankimport-Workflow und Android-26-Fix.
- GitHub Release: https://github.com/Apfelkringel/kontoklar-android/releases/tag/v0.83.4

## v0.83.3 – 17.09.2026

- Vorheriger stabiler Stand (Build 86), identisches Sicherheitsprofil.

## Quellenarchiv für Drive

Das vollständige Quellarchiv enthält neben `app/` auch `banking-api/` (Cloudflare-Worker, Migrationen und Tests), aber keine Build-Artefakte, `node_modules/`, `.dev.vars` oder den privaten Ordner `signing/`.
