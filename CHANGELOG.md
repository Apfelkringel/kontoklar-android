# KontoKlar – Release Notes

Alle veröffentlichten Versionen werden signiert über GitHub Actions bereitgestellt; die App lädt Updates direkt aus dem öffentlichen GitHub-Release und verifiziert SHA-256, Paket-ID, Versionscode und Signaturzertifikat, bevor sie Android den Paketinstaller anzeigt.

## v0.84.0 – 22.09.2026

- Projektverwaltung mit Kundenbezug, Umsatz-/Kosten-/Ergebnisübersicht und Projektauswahl in Rechnungs- und Ausgabenerfassung.
- Arbeitszeiterfassung pro Projekt mit Datum, Minuten, Stundensatz und Notiz; Einträge werden verschlüsselt gespeichert und gesichert.
- Bankseite mit Schnellaktionen für C24, comdirect und Trade Republic sowie klarer Rückmeldung zur verfügbaren Anbieterabdeckung.
- Version 93 / 0.84.0.

## v0.84.1 – 22.09.2026

- Stabilerer GitHub-Release-Prozess für Updates.
- APK wird zusammen mit einer SHA-256-Prüfsummendatei veröffentlicht.
- Version 94 / 0.84.1.

## v0.84.2 – 22.09.2026

- Direkte Import-Schaltflächen für C24, comdirect und Trade Republic im Bank-Dialog.
- Version 95 / 0.84.2.

## v0.84.3 – 22.09.2026

- Versionsverlauf-Anfragen im Update-Bereich begrenzt, damit nur ein kleiner, kontrollierter GitHub-Datensatz geladen wird.
- Version 96 / 0.84.3.

## v0.84.4 – 22.09.2026

- Projekte und Zeiterfassungen werden jetzt im Buchhaltungs- und Steuerberater-CSV-Export mitgeführt.
- Version 97 / 0.84.4.

## v0.84.5 – 22.09.2026

- Rechnungen können aus dem Detaildialog als neue Entwürfe dupliziert werden.
- Neue Rechnungsnummer und Fälligkeit werden automatisch vergeben; Zahlungen werden nicht übernommen.
- Version 98 / 0.84.5.

## v0.83.9 – 20.09.2026

- Weitere kostenlose Bankimporte: CSV-Parser unterstützt jetzt auch DKB („Betrag (EUR)“), ING („Saldo nach Buchung“) und N26-Exporte neben C24 und comdirect.
- Verschlüsseltes Backup direkt in den gewählten Google-Drive-Ordner: SAF-Picker öffnet den zuletzt verwendeten Drive-Ordner, Datei wird AES-verschlüsselt gespeichert.
- Neuer Einstellungspunkt „Open-Source-Lizenzen“ mit allen verwendeten Bibliothekslizenzen.
- Deutsch/Englisch-Lokalisierung der Kern-Navigation, des Dokumente-Dialogs und der MoreScreen-Menüeinträge.
- Version 92 / 0.83.9.

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
