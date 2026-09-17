# Community-Lösungen für kostenlose Bankanbindungen

Recherche: 17.09.2026. Ziel ist eine dauerhaft kostenlose, read-only Lösung für die eigenen Konten von C24, comdirect und Trade Republic.

## Ergebnis

| Bank / Weg | Community-Fund | Bewertung für KontoKlar |
|---|---|---|
| comdirect REST | [wuttke/comdirect-api](https://github.com/wuttke/comdirect-api), außerdem [python-comdirect-api](https://github.com/keisentraut/python-comdirect-api) und [go-comdirect](https://github.com/jsattler/go-comdirect) | Beste kostenlose Option für das eigene comdirect-Konto. Die Bank stellt eine offizielle REST-API für eigene Anwendungen bereit; eigene OAuth-Clientdaten und Session-TAN bleiben erforderlich. Der native Kotlin-Adapter liegt in `app/src/main/java/de/kontoklar/app/ComdirectReadOnly.kt`. Zusätzlich verarbeitet der CSV-Importer ältere ISO-8859-15-Exporte. |
| C24 Export/PDF | [C24toStarmoney](https://github.com/nasahl/C24toStarmoney) und der [Community-PDF-Importer](https://forum.portfolio-performance.info/t/pdf-import-von-c24-bank-gmbh/28636) | Der Web-CSV-Export und das C24-PDF sind als lokale, kostenlose Wege dokumentiert. KontoKlar verarbeitet beide direkt; beim PDF werden Datumszeile, Soll/Haben-Betrag und mehrzeilige Zahlungsinformationen streng erkannt. |
| C24 FinTS/HBCI | [hbci4java](https://github.com/hbci4j/hbci4java), [python-fints](https://github.com/raphaelm/python-fints) und [FinTS-Bankenliste](https://www.fints.org/de/hersteller/bankenliste) | FinTS ist als Protokoll frei verfügbar, aber für C24 ist in der geprüften offiziellen Dokumentation kein unabhängiger C24-FinTS-Endpunkt bestätigt. C24 beschreibt seinen PSD2-XS2A-Zugang über finAPI. Deshalb wird kein nicht verifizierter Host in die App eingebaut. |
| Trade Republic privat | [Offizieller Kontoauszug/Transaktions-Export](https://support.traderepublic.com/de-de/267), [pytr](https://github.com/pytr-org/pytr), [tr-api](https://github.com/cdamken/tr-api), [clitr](https://github.com/rtfpessoa/clitr) | KontoKlar importiert den offiziellen TR-CSV-Export (`datetime,date,...,amount,fee,tax,...,transaction_id`) und TR-PDFs lokal. Beim nativen Export werden Betrag, Gebühr und Steuer zum Nettowert addiert; die Transaktions-ID verhindert Duplikate. Wenn der offizielle Export im Konto noch nicht sichtbar ist, kann pytr außerhalb der App eine CSV erzeugen. Community-Abrufe mit privaten Endpunkten laufen weiterhin außerhalb der App. |

## Architekturentscheidung

1. **comdirect:** offizielle REST-API, ausschließlich read-only. KontoKlar speichert keine PIN, kein Passwort und keinen OAuth-Token im neuen Adapter. Die Session-TAN muss vom Nutzer selbst bestätigt werden.
2. **C24:** lokale Verarbeitung offizieller CSV-/XLSX-/PDF-/CAMT.053-Exporte. Ein direkter Adapter kommt erst hinzu, wenn C24 einen offiziell dokumentierten kostenlosen Zugang für diesen Zweck bestätigt.
3. **Trade Republic:** lokaler PDF-/CSV-Import. Zusätzlich kann ein Nutzer außerhalb der App mit [pytr](https://github.com/pytr-org/pytr) `account_transactions.csv` erzeugen und in KontoKlar auswählen. Keine privaten WebSocket-/REST-Endpunkte, kein Cookie-Import und kein Login-Scraper in KontoKlar.
4. **FinTS:** kann später als optionaler generischer Adapter geprüft werden. Vor einer Aktivierung müssen Bankparameterdaten, TAN-Verfahren, Datenschutz und Android-Credential-Sicherheit mit echten Testkonten verifiziert werden; die allgemeine FinTS-Bankenliste allein ist dafür kein ausreichender Nachweis.

## Warum kein „kostenloser Aggregator“

Ein Community-Client kann Softwarekosten vermeiden, ersetzt aber weder die Bankfreigabe noch eine mögliche regulatorische Rolle für eine Anwendung mit mehreren Nutzern. Für den persönlichen Betrieb bleibt die lokale Importlösung tatsächlich kostenlos. Ein automatischer Live-Abruf aller drei Banken ohne Anbietergebühr ist durch die geprüften Community-Projekte nicht belastbar belegt.

## Trade-Republic-Workflow ohne Kosten

1. In Trade Republic zuerst Profil → Dokumente/Kontoauszug bzw. Statements → Transaktions-Export öffnen und die CSV oder den Kontoauszug herunterladen. Dieser offizielle Export ist der bevorzugte Weg, weil kein Community-Login nötig ist.

2. Nur falls der offizielle Export fehlt: Auf dem eigenen Rechner [uv](https://docs.astral.sh/uv/) installieren und `uvx pytr@latest` verwenden; alternativ pytr nach der Projektanleitung installieren.
3. `uvx pytr@latest login` starten und die Freigabe ausschließlich in der eigenen Trade-Republic-App bzw. dem eigenen Authenticator bestätigen. Zugangsdaten und Codes niemals in KontoKlar eingeben.
4. Mit `uvx pytr@latest export_transactions` die `account_transactions.csv` erzeugen. `uvx pytr@latest dl_docs ./tr-export` erzeugt zusätzlich Dokumente und ebenfalls den Transaktionsexport.
5. In KontoKlar die CSV oder PDF auswählen. Die Datei wird ausschließlich auf dem Android-Gerät verarbeitet.

Das pytr-CSV ist ein Community-Export auf Basis privater Trade-Republic-Endpunkte und kann sich ändern. KontoKlar akzeptiert nur eindeutig erkennbare Buchungszeilen und rät bei Fehlern zu einem neuen Export; PINs, SMS-Codes, Cookies und JSON-Rohdaten werden nicht importiert. pytr unterstützt laut eigener Dokumentation weiterhin den Web-Login per `login` und optional `login --v2`; die Freigabe bleibt vollständig außerhalb von KontoKlar.

## Quellen

- [comdirect REST API für eigene Anwendungen](https://www.comdirect.de/cms/kontakt-zugaenge-api.html)
- [C24: PSD2-/XS2A-Zugang über finAPI](https://hilfe.c24.de/hc/de/articles/360017014279-Wie-ist-PSD2-bei-der-C24-Bank-umgesetzt)
- [Trade Republic: offizielle PSD2-Seite](https://traderepublic.com/de-de/psd2/account-information)
- [Trade Republic `tr-api` Community-Projekt](https://github.com/cdamken/tr-api)
- [Trade Republic `pytr` Community-Projekt und CSV-Export](https://github.com/pytr-org/pytr)
- [Trade Republic `clitr` Community-Projekt](https://github.com/rtfpessoa/clitr)
- [hbci4java](https://github.com/hbci4j/hbci4java)
- [FinTS-Bankenliste mit Aktualitätshinweis](https://www.fints.org/de/hersteller/bankenliste)
