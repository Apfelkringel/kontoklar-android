# Live Open Banking

## Was bereits implementiert ist

Die Android-App spricht ausschließlich mit KontoKlars HTTPS-Backend. Im Backend liegt ein Cloudflare-Worker-Adapter für finAPI mit isoliertem Provider-Profil je Installation, serverseitiger Bankauswahl, gehostetem finAPI-Freigabefluss, Umsatznormalisierung, manueller Synchronisierung und Trennen einer Verbindung. D1 hält nur Installations-Hashes, Provider-Auftragsreferenzen und kurzlebige Rate-Limit-Zähler; Zugangsdaten und Umsätze werden dort nicht gespeichert. Umsätze werden auf dem Gerät gespeichert. finAPI- und Bankschlüssel gehören niemals in die APK, GitHub, Drive oder diesen Chat.

Der Android-Client speichert einen zufälligen Installationsschlüssel in verschlüsselten App-Einstellungen und sendet ihn nur an die konfigurierte HTTPS-API. Ohne API-Basisadresse und korrekt eingerichtetes Backend bleiben Live-Verbindungen aus; lokaler CAMT.053-/CSV-/PDF-Import funktioniert unabhängig davon.

## Lokales Prüfen

Im Projektordner:

```sh
cd banking-api
npm ci
npm run check
```

Das prüft Worker-Bündelung, TypeScript und lokale Tests mit simulierten Providerantworten. Es kontaktiert keine echte Bank und veröffentlicht nichts.

## Eigenes Sandbox-Deployment

Voraussetzung ist ein finAPI-Sandbox-Vertrag/-Zugang, der für die benötigten Bank- und Kontotypen freigeschaltet ist, sowie ein Cloudflare-Konto. Die D1-Datenbank `kontoklar-banking-identities` ist bereits mit EU-Jurisdiktion erstellt; ihre ID steht in `wrangler.jsonc`. Beide Migrationen sind bereits remote angewendet. Im Ordner `banking-api`:

1. Nutze ausschließlich die Sandbox-Client-Credentials von finAPI. Der sichere Erst-Deploy-Assistent `npm run deploy:sandbox:first -- --check-only` prüft zuerst, ob D1, Konfiguration und Wrangler-Dry-Run bereit sind. Starte danach `npm run deploy:sandbox:first` in einem echten Terminal und gib Client-ID sowie Client-Secret verdeckt ein. Das Skript erzeugt einen zufälligen `INSTALLATION_PEPPER`, zeigt ihn einmal zur sicheren Ablage im Passwortmanager an und übergibt alle drei Werte über eine kurzlebige Datei mit Dateirechten `0600` an Wrangler. Das Skript verweigert einen erneuten Erst-Deploy, um eine unbeabsichtigte Pepper-Rotation zu vermeiden, und entfernt die Secret-Datei nach dem Deployment. Keine Credentials oder Pepper in Git, `.dev.vars`, Shell-History oder Chat ablegen. Bei Offenlegung müssen finAPI-Credentials rotiert werden. Den Pepper nicht spontan ersetzen: Provider-Passwörter werden davon abgeleitet, eine Rotation benötigt eine geplante Migration aller Provider-Identitäten.
2. Prüfe `FINAPI_ACCESS_BASE_URL` und `FINAPI_WEBFORM_BASE_URL` in `wrangler.jsonc`: die vorgegebenen Adressen sind Sandbox. Erst mit verifiziertem Live-Vertrag darf auf Live-Adressen gewechselt werden.
3. Prüfe `MAX_INSTALLATIONS` (Voreinstellung 1000) und `MAX_NEW_INSTALLATIONS_PER_MINUTE` (Voreinstellung 20) in `wrangler.jsonc` und setze für das erwartete Nutzungsvolumen passende positive Ganzzahlen. Der Worker begrenzt neue Installationen global und pro IP; die globale Grenze muss vor öffentlicher Live-Schaltung gegen finAPI-Vertragskontingente und Cloudflare-Kosten abgeglichen werden.
4. Führe `npm run check` aus und anschließend den Erst-Deploy-Assistenten wie oben beschrieben. Ein erster, secret-freier Deploy ist nicht möglich, weil die drei Secrets als erforderlich deklariert sind. Nach erfolgreichem Erstellen des Workers lassen sich einzelne Werte interaktiv über `npx wrangler secret put <NAME>` pflegen. Notiere die ausgegebene HTTPS-Worker-URL.
5. Baue Android mit `-PKONTOKLAR_BANKING_API_BASE_URL=https://<dein-worker>`; diese Adresse ist keine geheime Information. Das kann in `~/.gradle/gradle.properties` oder CI stehen, nicht in `local.properties`, das versehentlich committed wird.

Cloudflare-Status am 16.09.2026: EU-D1 ist erstellt und migriert; ein Worker wurde noch nicht deployed. Der Deploy wurde von Wrangler korrekt abgelehnt, weil die drei erforderlichen Secrets noch fehlen. Diese Anleitung führt den ersten Deploy nicht automatisch aus. D1 und Workers haben Free-Tier-Inklusivmengen; bei höheren Kontingenten können Kosten entstehen. Vor einer öffentlichen Live-Schaltung sind Vertrag, Kontingent und Kosten zu prüfen. Erst nach Sandbox-Tests mit einem eigenen Testbankzugang sollte eine Live-Freigabe erwogen werden.

## Verbindungen und Anbieterabdeckung

Der Worker sucht C24, comdirect und Trade Republic im finAPI-Bankverzeichnis und zeigt nur Treffer an, die finAPI als AIS-fähig meldet. Das ist keine Bestätigung, dass jeder benötigte Kontotyp oder Depot-/Umsatzumfang in deinem konkreten Vertrag verfügbar ist. Das muss finAPI für die konkreten Kennungen und den Vertrag schriftlich bestätigen. Insbesondere Depots und Trade-Republic-Funktionen dürfen nicht allein aus einem Bankverzeichnis-Treffer abgeleitet werden.

Verbindungen laufen über das gehostete finAPI-Webformular; KontoKlar fragt niemals Bank-PIN oder TAN ab. KontoKlar fordert aktuell Zahlungskonten (`CHECKING`) an und überspringt nicht genutzte Salden- und Wertpapierpositionsdownloads, damit nicht unterstützte Kontotypen keine zusätzlichen Schritte im Freigabefluss verursachen. Wertpapierdepots/Bestände werden noch nicht in der App dargestellt. Die App startet Synchronisierungen nur nach deinem Tippen. Ein Anbieter kann trotzdem eine erneute Freigabe/SCA erfordern. Es gibt keine Zahlungsinitiierung und keine stille/unbeaufsichtigte Synchronisierung.

Vor einer echten Veröffentlichung zusätzlich erforderlich: Anbieterfreigabe und Produktionszugang, tatsächlicher Test mit C24/comdirect/Trade Republic, rechtliche/PSD2-Rollenprüfung, Datenschutzhinweise und Auftragsverarbeitungsvertrag, Lösch-/Widerrufsprozess, Sicherheitsprüfung und Update-Signatur/Release-Prozess. Bis diese Punkte vorliegen, ist die Verbindung eine implementierte, aber nicht produktionsfreigegebene Integration.

## Datenschutz

Ein Kontoauszug enthält typischerweise IBAN, Name, Adresse, Salden und detaillierte Zahlungspartner-/Verwendungszwecke. Diese Angaben sind für die Integrationsarbeit nicht erforderlich. Daher bitte niemals vollständige Kontoauszüge oder Screenshots mit echten Daten in den Chat hochladen; falls ein Beispielformat gebraucht wird, zunächst lokal Name, IBAN, Adresse, Salden sowie Buchungsdetails schwärzen. Niemals Zugangsdaten, PIN, TAN oder Einmalcodes teilen.

## Technische Referenzen

* [finAPI Web Form 2.0: Ablauf, 20-Minuten-Gültigkeit und Statusabfrage](https://documentation.finapi.io/webform/web-form-2-0-basics)
* [finAPI Bankverbindung mit Web Form 2.0 importieren](https://documentation.finapi.io/access/import-a-new-bank-connection-with-web-form-2-0-rec)
* [finAPI Bankverbindung nutzerinitiiert aktualisieren (inkl. PSU-Metadaten und SCA)](https://documentation.finapi.io/access/update-a-bank-connection-for-web-form-2-0-customer)
* [finAPI Import-/Update-Nachverarbeitung und Kontostatus](https://documentation.finapi.io/access/post-processing-of-bank-account-import-update)
* [finAPI Sandbox- und Live-Umgebungen](https://documentation.finapi.io/webform/web-form-2-0-environments)
