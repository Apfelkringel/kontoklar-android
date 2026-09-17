# Live Open Banking

## Was bereits implementiert ist

Die Android-App spricht ausschließlich mit KontoKlars HTTPS-Backend. Im Backend liegt ein Cloudflare-Worker-Adapter für finAPI mit isoliertem Provider-Profil je Installation, serverseitiger Bankauswahl, gehostetem finAPI-Freigabefluss, Umsatznormalisierung, manueller Synchronisierung und Trennen einer Verbindung. D1 hält nur Installations-Hashes, Provider-Auftragsreferenzen und kurzlebige Rate-Limit-Zähler; Zugangsdaten und Umsätze werden dort nicht gespeichert. Umsätze werden auf dem Gerät gespeichert. finAPI- und Bankschlüssel gehören niemals in die APK, GitHub, Drive oder diesen Chat.

Der Android-Client speichert einen zufälligen Installationsschlüssel in verschlüsselten App-Einstellungen und sendet ihn nur an die konfigurierte HTTPS-API. Ohne API-Basisadresse und korrekt eingerichtetes Backend bleiben Live-Verbindungen aus; lokaler CAMT.053-/CSV-/XLSX-/PDF-Import funktioniert unabhängig davon. Der C24-Excel-Import liest das erste Tabellenblatt lokal, begrenzt die Datei und akzeptiert nur erkannte Buchungsdatum-/Betragsspalten.

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

Verbindungen laufen über das gehostete finAPI-Webformular; KontoKlar fragt niemals Bank-PIN oder TAN ab. Die App bietet direkte Schnellwahl für C24, comdirect und Trade Republic sowie eine allgemeine Banksuche im finAPI-Webformular (Web Form 2.0 wählt dort Bank und Schnittstelle selbst aus). Vor dem Start kann der Nutzer wählen, ob zusätzlich zu Girokonten (`CHECKING`) auch Wertpapierdepots (`SECURITY`) angefordert werden. Das vermeidet unnötige Depot-Freigabeschritte, wenn nur Umsätze benötigt werden; finAPI kann für unterschiedliche Kontotypen unterschiedliche Protokolle und Freigaben durchlaufen. Verfügbare Konten/Bestände werden lokal verschlüsselt gespeichert und in der Banking-Ansicht angezeigt; die App summiert keine Währungen, berechnet weder Performance noch Steuern und garantiert nicht, dass jede Bank/Vertragskonfiguration diese Daten liefert. Die App startet Synchronisierungen nur nach deinem Tippen. Ein Anbieter kann trotzdem eine erneute Freigabe/SCA erfordern. Es gibt keine Zahlungsinitiierung und keine stille/unbeaufsichtigte Synchronisierung.

### Funktionsumfang und Vertragsrealität

„Bank verbinden“ deckt nicht automatisch alle Konten bei derselben Bank ab. finAPI empfiehlt, nur die benötigten Kontotypen anzufordern; `CHECKING`, `CREDIT_CARD`, `SAVINGS` und `SECURITY` können unterschiedliche Bankprotokolle und weitere Freigabeschritte auslösen. KontoKlar unterstützt derzeit die explizite Auswahl Girokonto (`CHECKING`) und optional Depot (`SECURITY`); Kreditkarten und Tagesgeld sind noch nicht auswählbar. Eine App-Anfrage oder ein Verzeichnistreffer garantiert nicht, dass der eigene Vertrag genau diese Verbindung und Kontotypen freischaltet. Vor Live-Einsatz muss finAPI die gewünschten Banken, Kontotypen und Depotdaten für den konkreten Vertrag bestätigen.

Ein Sandbox-Zugang dient der Entwicklung und ist kein Live-Bankzugang. Laut finAPI-Preisseite (17.09.2026) beginnen Access B2C bei 60 €/Monat und B2X bei 100 €/Monat bis jeweils 200 Nutzer. Bei nicht bereits PSD2-lizenzierten Anbietern nennt finAPI zusätzlich ab 200 €/Monat für die Kontoinformationsdienst-Lizenz (KID); das sind somit mindestens 260 €/Monat B2C bzw. 300 €/Monat B2X vor MwSt., sofern diese Lizenz erforderlich ist. Für höchstens 10 eigene interne Konten wird „Access für Eigenanwender“ für 200 €/Monat angeboten; finAPI sagt, dass dafür keine zusätzliche PSD2-Lizenz nötig ist. Die Online-Bestellung nennt eine anfängliche Laufzeit von 24 Monaten. Für dieses Einzelpersonen-Projekt scheint Eigenanwender der passende Tarifkandidat, aber finAPI muss vor Bestellung schriftlich bestätigen, dass Nutzung für die eigene Buchhaltung sowie genau C24, comdirect (inkl. Depot) und Trade Republic im gewünschten Datenumfang abgedeckt sind. Deshalb wird kein Vertrag abgeschlossen und kein kostenpflichtiger Zugang ohne gesonderte ausdrückliche Kaufentscheidung eingerichtet. Preise und Bedingungen vor einer Entscheidung direkt beim Anbieter prüfen.

### Recherche zur möglichst einfachen Anbindung (Stand 17.09.2026)

„Wie Finanzguru“ ist nicht einfach eine einzelne Bankschnittstelle: Finanzguru beschreibt XS2A für Girokonten und FinTS für Kreditkarten, Spar-/Tagesgeld und Depots; die Kontoauswahl und je nach Bank wiederkehrende TAN-Freigaben bleiben Teil des Ablaufs. Der Komfort kommt daher vor allem vom Aggregator und seiner Banksuche, den bank-spezifischen Freigabewegen und der Pflege der Verbindungen.

| Bank | Verifizierte offizielle Information | Konsequenz für KontoKlar |
|---|---|---|
| C24 | C24 sagt, sein XS2A-Server werde mit finAPI bereitgestellt; Freigabe führt zur C24-App. | finAPI ist für ein PSD2-Girokonto der naheliegende einheitliche Weg. Ein konkreter finAPI-Livevertrag und ein erfolgreicher End-to-End-Test sind weiter erforderlich. |
| comdirect | comdirect bietet neben PSD2 eine kostenlose REST-API für eigene Anwendungen; genannt werden Konten, Karten, Depotübersicht, Salden und Umsätze. Client-Zugangsdaten werden im persönlichen Bereich verwaltet; TAN kann für API-Aktionen gebraucht werden. | Für den eigenen comdirect-Zugang könnte eine separate direkte Integration Depotdaten ergänzen. Sie ist nicht automatisch eine einfache „Bank auswählen und freigeben“-Anbindung für beliebige KontoKlar-Nutzer und benötigt eine eigene Prüfung von OAuth, Session-TAN und Zugangsdaten. |
| Trade Republic | TR verweist Drittanbieter auf ein Verfahren zur Freischaltung der PSD2-Open-Banking-Schnittstelle. Die eingesehene Enable-Banking-Marktabdeckung beschreibt für TR nur Betrag, Währung und Buchungsdatum, nicht Zweck, Gegenpartei oder Wertpapierpositionen. | Die geplante Aggregator-Anbindung darf nicht als vollständiger TR-Depot-/Umsatzimport beworben werden. Umfang und Zulassung müssen finAPI für genau diesen Vertrag schriftlich bestätigen und anschließend praktisch getestet werden. Keine inoffiziellen App-Endpunkte oder Passwort-Scraper einsetzen. |

Vergleich: GoCardless Bank Account Data dokumentiert PSD2-Kontodetails, Salden und Umsätze, aber keine Wertpapierpositions-API; außerdem ist die Nutzung an Providerkontingente gebunden. Tink bewirbt gehostete Authentifizierung und breite EU-Abdeckung, doch die öffentlich geprüften Seiten belegen nicht spezifisch C24, comdirect-Depots und TR-Positionen zusammen. Ein Wechsel dorthin löst daher die offene Coverage-Frage nicht ohne schriftliche Anbieterbestätigung und Vertrag.

**Entscheidung für den jetzigen Stand:** die App behält finAPI als einen gehosteten Zustimmungs-/Aggregator-Flow, statt Nutzern Bank-PINs direkt in KontoKlar abzufragen. Das ist am ehesten „einfach verbinden“, wenn der Anbietervertrag die gewünschten Banken und Kontotypen abdeckt. Eine zweite comdirect-API-Integration ist erst sinnvoll, falls finAPI dort keine Depotpositionen liefert und der persönliche API-/TAN-Ablauf akzeptabel ist. Das bleibt bis zu einer solchen Freigabe und einem Live-Test unvollständig.

Enable Banking offers a no-fee restricted production mode for evaluation or private use, but its current terms explicitly exclude business/professional use, business accounts, and commercial use. That is not an eligible provider mode for KontoKlar's bookkeeping purpose and must not be used as a workaround for a production agreement. Its Germany coverage page lists Trade Republic, but says its PSD2 API only supplies amount, currency, and booking date (as of August 2026); the official material reviewed here does not confirm C24 or comdirect. See [Enable Banking's restricted-mode setup](https://enablebanking.com/docs/api/linked-accounts), [current Terms of Service § Restriction of Use](https://enablebanking.com/terms/), and [Germany coverage](https://enablebanking.com/docs/markets/de/).

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
* [finAPI Online-Bestellung: initiale Vertragslaufzeit und Eigenanwender-Produkte](https://www.finapi.io/jetzt-bestellen/)
* [Finanzguru: XS2A für Girokonten und FinTS für weitere Kontotypen](https://hilfe.finanzguru.de/de/articles/1558594)
* [C24: PSD2-Umsetzung, finAPI-XS2A und technische Informationen](https://hilfe.c24.de/hc/de/articles/360017014279-Wie-ist-PSD2-bei-der-C24-Bank-umgesetzt)
* [comdirect: REST-API für eigene Anwendungen, Konten und Depots](https://www.comdirect.de/cms/kontakt-zugaenge-api.html)
* [Trade Republic: offizielle PSD2 Account-Information-Seite](https://traderepublic.com/de-de/psd2/account-information)
* [Finanzguru: aktueller Bankenstatus](https://integ.finanzguru.de/status)
* [GoCardless: Bank Account Data – Datenumfang und Journey](https://docs.gocardless.com/docs/bank-account-data)
* [Tink: Account Aggregation und Authentifizierungsablauf](https://tink.com/de/produkte/transactions/)
* [Enable Banking: eingeschränkter Produktionszugang für eigene Konten](https://enablebanking.com/docs/api/linked-accounts)
* [Enable Banking: Nutzungsbeschränkungen und Preise](https://enablebanking.com/terms/)
* [Enable Banking Deutschland: Trade-Republic-Datenumfang](https://enablebanking.com/docs/markets/de/)
