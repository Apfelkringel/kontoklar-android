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
5. Für lokale Builds baue Android mit `-PKONTOKLAR_BANKING_API_BASE_URL=https://<dein-worker>`; diese Adresse ist keine geheime Information. Für signierte GitHub-Releases setze die Repository-Variable `KONTOKLAR_BANKING_API_BASE_URL` auf die HTTPS-Worker-URL. Der Release-Workflow übernimmt sie in den APK-Build. Solange sie leer ist, bleibt Live-Banking in der App deaktiviert. Provider-Secrets gehören weiterhin ausschließlich in Cloudflare Worker Secrets, nicht in GitHub-Variablen oder die APK.

Cloudflare-Status am 16.09.2026: EU-D1 ist erstellt und migriert; ein Worker wurde noch nicht deployed. Der Deploy wurde von Wrangler korrekt abgelehnt, weil die drei erforderlichen Secrets noch fehlen. Diese Anleitung führt den ersten Deploy nicht automatisch aus. D1 und Workers haben Free-Tier-Inklusivmengen; bei höheren Kontingenten können Kosten entstehen. Vor einer öffentlichen Live-Schaltung sind Vertrag, Kontingent und Kosten zu prüfen. Erst nach Sandbox-Tests mit einem eigenen Testbankzugang sollte eine Live-Freigabe erwogen werden.

## Verbindungen und Anbieterabdeckung

Der Worker sucht C24, comdirect und Trade Republic im finAPI-Bankverzeichnis und zeigt nur Treffer an, deren einzelne Access-V2-Schnittstelle `isAisSupported=true` meldet; fehlende Schnittstellenangaben oder Capability-Flags allein reichen nicht. finAPI dokumentiert, dass Access V2 auch derzeit nicht unterstützte Schnittstellen zurückliefern kann. Ein AIS-fähiger Treffer belegt trotzdem nicht, dass jeder benötigte Kontotyp oder Depot-/Umsatzumfang in deinem konkreten Vertrag verfügbar ist. Das muss finAPI für die konkreten Kennungen und den Vertrag schriftlich bestätigen. Insbesondere Depots und Trade-Republic-Funktionen dürfen nicht allein aus einem Bankverzeichnis-Treffer abgeleitet werden.

Verbindungen laufen über das gehostete finAPI-Webformular; KontoKlar fragt niemals Bank-PIN oder TAN ab. Die App bietet sowohl direkte Schnellwahl für C24, comdirect und Trade Republic als auch eine allgemeine Banksuche im finAPI-Webformular (Web Form 2.0 wählt dort Bank und Schnittstelle selbst aus). KontoKlar fordert aktuell Zahlungskonten (`CHECKING`) an und überspringt nicht genutzte Salden- und Wertpapierpositionsdownloads, damit nicht unterstützte Kontotypen keine zusätzlichen Schritte im Freigabefluss verursachen. Wertpapierdepots/Bestände werden noch nicht in der App dargestellt. Die App startet Synchronisierungen nur nach deinem Tippen. Ein Anbieter kann trotzdem eine erneute Freigabe/SCA erfordern. Es gibt keine Zahlungsinitiierung und keine stille/unbeaufsichtigte Synchronisierung.

### Funktionsumfang und Vertragsrealität

„Bank verbinden“ deckt nicht automatisch alle Konten bei derselben Bank ab. finAPI empfiehlt, nur die benötigten Kontotypen anzufordern; `CHECKING`, `CREDIT_CARD`, `SAVINGS` und `SECURITY` können unterschiedliche Bankprotokolle und weitere Freigabeschritte auslösen. KontoKlar fordert derzeit absichtlich nur `CHECKING` an. Die App kann daher noch nicht als vollständiger Finanzguru-Ersatz für Kreditkarten, Tagesgeld oder Wertpapierdepots gelten. Finanzguru beschreibt selbst XS2A für Girokonten und FinTS für weitere Kontotypen, einschließlich Depots. Auch eine Bank, die im finAPI-Verzeichnis auftaucht, garantiert nicht, dass der eigene Vertrag genau diese Verbindung und Kontotypen freischaltet.

Ein Sandbox-Zugang dient der Entwicklung und ist kein Live-Bankzugang. finAPI nennt auf der aktuellen Preisseite für Access B2C eine Grundgebühr von 60 €/Monat bis 200 Nutzer, für Access B2X 100 €/Monat, und für „Access für Eigenanwender“ (höchstens 10 eigene Konten) 200 €/Monat; PSD2-Lizenzkosten können für Nicht-Eigenanwender hinzukommen. finAPI weist bei der Bestellung auf eine anfängliche Vertragslaufzeit von 24 Monaten hin. Die Bestellung erfordert geschäftliche Kontaktdaten und eine Identitätsprüfung. Deshalb werden weder ein Produktionsvertrag abgeschlossen noch kostenpflichtige Zugänge oder Live-Credentials ohne separate ausdrückliche Entscheidung eingerichtet. Preise und Vertragsbedingungen vor einer Entscheidung direkt beim Anbieter prüfen.

Für eine rein private App-Nutzung ausschließlich mit eigenen Konten ist Enable Banking eine mögliche, noch nicht implementierte Alternative: Der Anbieter dokumentiert eine kostenlose eingeschränkte Produktionsnutzung für private Eigenkonten, nachdem jedes Konto im Anbieterportal verknüpft wurde. Der Modus gibt ausschließlich genau diese freigegebenen Konten zurück und ist nicht für Geschäftskonten, fremde Konten oder öffentliche/kommerzielle App-Nutzung bestimmt. Die Abdeckung ist für KontoKlar noch nicht ausreichend verifiziert: Enable Banking nennt Trade Republic, weist dort aber (Stand August 2026) nur Betrag, Währung und Buchungsdatum als verfügbare Transaktionsdaten aus; comdirect wurde in Anbieter-Änderungsnotizen erwähnt, C24 konnte ich in den offiziellen Unterlagen nicht bestätigen. Diese Alternative ersetzt deshalb weder die implementierte finAPI-Integration noch eine Prüfung der drei konkreten Zugänge.

Vor einer echten Veröffentlichung zusätzlich erforderlich: Anbieterfreigabe und Produktionszugang, tatsächlicher Test mit C24/comdirect/Trade Republic, rechtliche/PSD2-Rollenprüfung, Datenschutzhinweise und Auftragsverarbeitungsvertrag, Lösch-/Widerrufsprozess, Sicherheitsprüfung und Update-Signatur/Release-Prozess. Bis diese Punkte vorliegen, ist die Verbindung eine implementierte, aber nicht produktionsfreigegebene Integration.

## Datenschutz

Ein Kontoauszug enthält typischerweise IBAN, Name, Adresse, Salden und detaillierte Zahlungspartner-/Verwendungszwecke. Diese Angaben sind für die Integrationsarbeit nicht erforderlich. Daher bitte niemals vollständige Kontoauszüge oder Screenshots mit echten Daten in den Chat hochladen; falls ein Beispielformat gebraucht wird, zunächst lokal Name, IBAN, Adresse, Salden sowie Buchungsdetails schwärzen. Niemals Zugangsdaten, PIN, TAN oder Einmalcodes teilen.

## Technische Referenzen

* [finAPI Web Form 2.0: Ablauf, 20-Minuten-Gültigkeit und Statusabfrage](https://documentation.finapi.io/webform/web-form-2-0-basics)
* [finAPI Web Form 2.0: integrierte Banksuche und Interface-Auswahl](https://documentation.finapi.io/webform/about-web-form-2-0)
* [finAPI Bankverbindung mit Web Form 2.0 importieren](https://documentation.finapi.io/access/import-a-new-bank-connection-with-web-form-2-0-rec)
* [finAPI Bankverbindung nutzerinitiiert aktualisieren (inkl. PSU-Metadaten und SCA)](https://documentation.finapi.io/access/update-a-bank-connection-for-web-form-2-0-customer)
* [finAPI Import-/Update-Nachverarbeitung und Kontostatus](https://documentation.finapi.io/access/post-processing-of-bank-account-import-update)
* [finAPI Sandbox- und Live-Umgebungen](https://documentation.finapi.io/webform/web-form-2-0-environments)
* [finAPI Access: unterstützte Kontotypen und Schnittstellen](https://documentation.finapi.io/access)
* [finAPI: weitere Kontotypen bei der Web-Form-Verbindung anfordern](https://documentation.finapi.io/access/import-a-new-bank-connection-with-web-form-2-0-rec)
* [finAPI aktuelle Produktpreise und Vertragsbedingungen](https://www.finapi.io/preise/)
* [Finanzguru: XS2A für Girokonten und FinTS für weitere Kontotypen](https://hilfe.finanzguru.de/de/articles/1558594)
* [Enable Banking: eingeschränkter Produktionszugang für eigene Konten](https://enablebanking.com/docs/api/linked-accounts)
* [Enable Banking: Nutzungsbeschränkungen und Preise](https://enablebanking.com/terms/)
* [Enable Banking Deutschland: Trade-Republic-Datenumfang](https://enablebanking.com/docs/markets/de/)
