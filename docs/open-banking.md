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

Voraussetzung ist ein finAPI-Sandbox-Vertrag/-Zugang, der für die benötigten Bank- und Kontotypen freigeschaltet ist, sowie ein Cloudflare-Konto. Im Ordner `banking-api`:

1. Erzeuge eine D1-Datenbank mit `npx wrangler d1 create kontoklar-banking-identities` und trage die ausgegebene `database_id` in `wrangler.jsonc` ein. Diese ID ist keine geheime Zugangsinformation.
2. Installiere das Schema mit `npx wrangler d1 migrations apply kontoklar-banking-identities --remote`.
3. Setze die Sandbox-Secrets interaktiv mit `npx wrangler secret put FINAPI_CLIENT_ID`, `npx wrangler secret put FINAPI_CLIENT_SECRET` und `npx wrangler secret put INSTALLATION_PEPPER`. Für den Pepper verwende einen unabhängig erzeugten zufälligen Wert mit mindestens 32 Zeichen; verwahre ihn dauerhaft und verschlüsselt im Passwortmanager. Nicht in Git, `.dev.vars`, Shell-History oder Chat einfügen. Die finAPI-Client-Credentials müssen bei Offenlegung rotiert werden; den `INSTALLATION_PEPPER` nicht spontan ersetzen, da bestehende Provider-Passwörter daraus abgeleitet werden und dadurch Bankprofile unzugänglich würden. Eine Pepper-Rotation benötigt eine geplante Migration aller Provider-Identitäten.
4. Prüfe `FINAPI_ACCESS_BASE_URL` und `FINAPI_WEBFORM_BASE_URL` in `wrangler.jsonc`: die vorgegebenen Adressen sind Sandbox. Erst mit verifiziertem Live-Vertrag darf auf Live-Adressen gewechselt werden.
5. Prüfe `MAX_INSTALLATIONS` (Voreinstellung 1000) und `MAX_NEW_INSTALLATIONS_PER_MINUTE` (Voreinstellung 20) in `wrangler.jsonc` und setze für das erwartete Nutzungsvolumen passende positive Ganzzahlen. Der Worker begrenzt neue Installationen global und pro IP; die globale Grenze muss vor öffentlicher Live-Schaltung trotzdem gegen finAPI-Vertragskontingente und Cloudflare-Kosten abgeglichen werden.
6. Führe `npm run check` und danach bewusst `npx wrangler deploy` aus. Notiere die ausgegebene HTTPS-Worker-URL.
7. Baue Android mit `-PKONTOKLAR_BANKING_API_BASE_URL=https://<dein-worker>`; diese Adresse ist keine geheime Information. Das kann in `~/.gradle/gradle.properties` oder CI stehen, nicht in `local.properties`, das versehentlich committed wird.

Diese Schritte erstellen Cloudflare-Ressourcen und können je nach Kontotarif Kosten oder Vertragsfolgen haben. Hier wurden keine D1-Ressourcen angelegt, keine Secrets gesetzt und nichts deployed. Erst nach Sandbox-Tests mit einem eigenen Testbankzugang sollte eine Live-Freigabe erwogen werden.

## Verbindungen und Anbieterabdeckung

Der Worker sucht C24, comdirect und Trade Republic im finAPI-Bankverzeichnis und zeigt nur Treffer an, die finAPI als AIS-fähig meldet. Das ist keine Bestätigung, dass jeder benötigte Kontotyp oder Depot-/Umsatzumfang in deinem konkreten Vertrag verfügbar ist. Das muss finAPI für die konkreten Kennungen und den Vertrag schriftlich bestätigen. Insbesondere Depots und Trade-Republic-Funktionen dürfen nicht allein aus einem Bankverzeichnis-Treffer abgeleitet werden.

Verbindungen laufen über das gehostete finAPI-Webformular; KontoKlar fragt niemals Bank-PIN oder TAN ab. Die App startet Synchronisierungen nur nach deinem Tippen. Ein Anbieter kann trotzdem eine erneute Freigabe/SCA erfordern. Es gibt keine Zahlungsinitiierung und keine stille/unbeaufsichtigte Synchronisierung.

Vor einer echten Veröffentlichung zusätzlich erforderlich: Anbieterfreigabe und Produktionszugang, tatsächlicher Test mit C24/comdirect/Trade Republic, rechtliche/PSD2-Rollenprüfung, Datenschutzhinweise und Auftragsverarbeitungsvertrag, Lösch-/Widerrufsprozess, Sicherheitsprüfung und Update-Signatur/Release-Prozess. Bis diese Punkte vorliegen, ist die Verbindung eine implementierte, aber nicht produktionsfreigegebene Integration.

## Datenschutz

Ein Kontoauszug enthält typischerweise IBAN, Name, Adresse, Salden und detaillierte Zahlungspartner-/Verwendungszwecke. Diese Angaben sind für die Integrationsarbeit nicht erforderlich. Daher bitte niemals vollständige Kontoauszüge oder Screenshots mit echten Daten in den Chat hochladen; falls ein Beispielformat gebraucht wird, zunächst lokal Name, IBAN, Adresse, Salden sowie Buchungsdetails schwärzen. Niemals Zugangsdaten, PIN, TAN oder Einmalcodes teilen.

## Technische Referenzen

* [finAPI Web Form 2.0: Ablauf, 20-Minuten-Gültigkeit und Statusabfrage](https://documentation.finapi.io/webform/web-form-2-0-basics)
* [finAPI Bankverbindung mit Web Form 2.0 importieren](https://documentation.finapi.io/access/import-a-new-bank-connection-with-web-form-2-0-rec)
* [finAPI Bankverbindung nutzerinitiiert aktualisieren (inkl. PSU-Metadaten und SCA)](https://documentation.finapi.io/access/update-a-bank-connection-for-web-form-2-0-customer)
* [finAPI Import-/Update-Nachverarbeitung und Kontostatus](https://documentation.finapi.io/access/post-processing-of-bank-account-import-update)
* [finAPI Sandbox- und Live-Umgebungen](https://documentation.finapi.io/webform/web-form-2-0-environments)
